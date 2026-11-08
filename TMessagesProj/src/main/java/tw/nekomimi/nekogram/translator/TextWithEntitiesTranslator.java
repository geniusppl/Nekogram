package tw.nekomimi.nekogram.translator;

import com.google.net.cronet.okhttptransport.CronetCallFactory;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranslateAlert2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.nekogram.translator.BaiduTranslator;
import app.nekogram.translator.BaseTranslator;
import app.nekogram.translator.DeepLTranslator;
import app.nekogram.translator.GoogleAppTranslator;
import app.nekogram.translator.LingoTranslator;
import app.nekogram.translator.MicrosoftTranslator;
import app.nekogram.translator.SogouTranslator;
import app.nekogram.translator.TranSmartTranslator;
import app.nekogram.translator.TranslatorConfig;
import app.nekogram.translator.YandexTranslator;
import app.nekogram.translator.YouDaoTranslator;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.CronetHelper;
import tw.nekomimi.nekogram.translator.deepl.DeepLOAuth;
import tw.nekomimi.nekogram.translator.html.HTMLKeeper;

public class TextWithEntitiesTranslator implements Translator.ITranslator {

    private static final HashMap<String, TextWithEntitiesTranslator> wrappedTranslators = new HashMap<>();
    private static boolean configuredCallFactory = false;

    public static TextWithEntitiesTranslator of(String type) {
        if (!configuredCallFactory) {
            TranslatorConfig.setCallFactory(buildCallFactory());
            configuredCallFactory = true;
        }
        return wrappedTranslators.computeIfAbsent(type, type1 -> {
            var translator = switch (type1) {
                case Translator.PROVIDER_YANDEX -> YandexTranslator.getInstance();
                case Translator.PROVIDER_LINGO -> LingoTranslator.getInstance();
                case Translator.PROVIDER_DEEPL -> DeepLTranslator.getInstance();
                case Translator.PROVIDER_MICROSOFT -> MicrosoftTranslator.getInstance();
                case Translator.PROVIDER_YOUDAO -> YouDaoTranslator.getInstance();
                case Translator.PROVIDER_BAIDU -> BaiduTranslator.getInstance();
                case Translator.PROVIDER_SOGOU -> SogouTranslator.getInstance();
                case Translator.PROVIDER_TENCENT -> TranSmartTranslator.getInstance();
                default -> GoogleAppTranslator.getInstance();
            };
            return new TextWithEntitiesTranslator(translator);
        });
    }

    private static Call.Factory buildCallFactory() {
        if (CronetHelper.isAvailable()) {
            var builder = CronetCallFactory.newBuilder(CronetHelper.getEngine());
            builder.setCallTimeoutMillis(120 * 1000);
            builder.setReadTimeoutMillis(120 * 1000);
            builder.setWriteTimeoutMillis(120 * 1000);
            return builder.build();
        } else {
            var builder = new OkHttpClient.Builder();
            builder.connectTimeout(120, TimeUnit.SECONDS);
            builder.readTimeout(120, TimeUnit.SECONDS);
            builder.writeTimeout(120, TimeUnit.SECONDS);
            return builder.build();
        }
    }

    private final BaseTranslator translator;

    private TextWithEntitiesTranslator(BaseTranslator translator) {
        this.translator = translator;
    }

    @Override
    public Translator.TranslationResult translate(TLRPC.TL_textWithEntities query, String fl, String tl) throws Exception {
        if (translator instanceof DeepLTranslator) {
            DeepLOAuth.configureAccessToken();
        }
        if (NekoConfig.keepFormatting) {
            var html = HTMLKeeper.entitiesToHtml(query.text, query.entities, false);
            var result = translator.translate(html, null, tl);
            String translated = result.getTranslation();
            if (sameLanguage(result.getSourceLanguage(), tl)) {
                translated = translateMixed(html, tl, true);
            }
            var textAndEntitiesTranslated = HTMLKeeper.htmlToEntities(translated, query.entities, false);
            return Translator.TranslationResult.of(
                    TranslateAlert2.preprocess(query, textAndEntitiesTranslated),
                    result.getSourceLanguage()
            );
        } else {
            var result = translator.translate(query.text, null, tl);
            String translated = result.getTranslation();
            if (sameLanguage(result.getSourceLanguage(), tl)) {
                translated = translateMixed(query.text, tl, false);
            }
            return Translator.TranslationResult.of(
                    Translator.textWithEntities(translated, null)
                    , result.getSourceLanguage());
        }
    }

    // Smart boost: mixed-language messages. When the whole message is detected as the
    // target language, the engine returns it unchanged and foreign paragraphs stay
    // untranslated. Translate it line group by line group instead, keeping groups that
    // are already in the target language, code blocks and bare links as they are.
    private static final Pattern TAG = Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9\\-]*)[^>]*?(/?)>");
    private static final Pattern ONLY_LINKS = Pattern.compile("^\\s*(https?://\\S+\\s*)+$");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}{2,}");
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);

    private static boolean sameLanguage(String a, String b) {
        if (a == null || b == null) return false;
        String x = a.toLowerCase(Locale.ROOT), y = b.toLowerCase(Locale.ROOT);
        int i = x.indexOf('-'), j = y.indexOf('-');
        if (i > 0) x = x.substring(0, i);
        if (j > 0) y = y.substring(0, j);
        return x.equals(y);
    }

    private static String stripTags(String s) {
        return TAG.matcher(s).replaceAll("");
    }

    private String translateMixed(String text, String tl, boolean html) throws Exception {
        String[] lines = text.split("\n", -1);
        List<String> groups = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int k = 0; k < lines.length; k++) {
            if (cur.length() > 0) cur.append('\n');
            cur.append(lines[k]);
            if (html) {
                Matcher m = TAG.matcher(lines[k]);
                while (m.find()) {
                    if (!m.group(3).isEmpty() || m.group(2).equalsIgnoreCase("br")) continue;
                    depth += m.group(1).isEmpty() ? 1 : -1;
                }
            }
            if (depth <= 0 || k == lines.length - 1) {
                groups.add(cur.toString());
                cur.setLength(0);
                depth = 0;
            }
        }
        List<Future<String>> futures = new ArrayList<>();
        for (String g : groups) {
            String plain = html ? stripTags(g) : g;
            boolean skip = !HAS_LETTER.matcher(plain).find()
                    || ONLY_LINKS.matcher(plain).matches()
                    || (html && g.contains("<tg-pre"));
            if (skip) {
                futures.add(null);
                continue;
            }
            futures.add(POOL.submit(() -> {
                try {
                    var r = translator.translate(g, null, tl);
                    if (sameLanguage(r.getSourceLanguage(), tl)) return g;
                    return r.getTranslation();
                } catch (Exception e) {
                    return g;
                }
            }));
        }
        StringBuilder out = new StringBuilder();
        for (int k = 0; k < groups.size(); k++) {
            if (k > 0) out.append('\n');
            Future<String> f = futures.get(k);
            String t = f == null ? groups.get(k) : f.get(60, TimeUnit.SECONDS);
            out.append(t != null ? t.replaceAll("[\\r\\n]+$", "") : groups.get(k));
        }
        return out.toString();
    }

    @Override
    public boolean supportLanguage(String language) {
        return translator.supportLanguage(language);
    }

    @Override
    public List<String> getTargetLanguages() {
        return translator.getTargetLanguages();
    }
}
