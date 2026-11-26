/* check.c - calls safe_store_call for every kind and prints what the safe wrapper reports (C, as Java sees it). */
#include <stdio.h>
#include "store_safe.h"

int main(void) {
    for (int kind = 0; kind <= 10; kind++) {
        int result = 0;
        int status = safe_store_call(kind, &result);
        if (status == SAFE_OK) printf("%2d  SAFE_OK                 result=%d\n", kind, result);
        else printf("%2d  %-22s  type=%-18s message=%s\n", kind,
                    status == SAFE_CPP_EXCEPTION ? "SAFE_CPP_EXCEPTION" : status == SAFE_UNKNOWN_EXCEPTION
                    ? "SAFE_UNKNOWN_EXCEPTION" : "SAFE_NULL_ARGUMENT", safe_last_error_type(), safe_last_error_message());
    }
    return 0;
}
