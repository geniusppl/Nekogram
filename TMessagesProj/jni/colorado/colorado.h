#include <stdbool.h>

#ifdef NDEBUG
#define LOG_DISABLED
#endif
#define PACKAGE_NAME "tw.nekomimi.nekogram"_iobfs.c_str()
#define CERT_HASH 0x8506d9a7
#define CERT_SIZE 0x54d

#ifdef __cplusplus
extern "C" {
#endif

bool check_signature();

#ifdef __cplusplus
}
#endif