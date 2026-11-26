#ifndef API_H
#define API_H
#include "types.h"
double big_sum(const Big *b);
void   big_fill(Big *out, int seed);
Big    big_make(int seed);
int    big_bump(Big *b);
#endif
