/* 40 fields: more than RecordGen's CHUNK (32), so the per-field code is split into methods. */
#ifndef TYPES_H
#define TYPES_H
#include <stdint.h>
typedef struct { double x; double y; } Point;
typedef enum { RED, GREEN, BLUE } Color;
typedef struct {
    int i0;
    double d1;
    int64_t l2;
    float f3;
    Point p4;
    int a5[3];
    char s6[8];
    Color c7;
    int i8;
    double d9;
    int64_t l10;
    float f11;
    Point p12;
    int a13[3];
    char s14[8];
    Color c15;
    int i16;
    double d17;
    int64_t l18;
    float f19;
    Point p20;
    int a21[3];
    char s22[8];
    Color c23;
    int i24;
    double d25;
    int64_t l26;
    float f27;
    Point p28;
    int a29[3];
    char s30[8];
    Color c31;
    int i32;
    double d33;
    int64_t l34;
    float f35;
    Point p36;
    int a37[3];
    char s38[8];
    Color c39;
} Big;
#endif
