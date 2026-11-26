enum color_e { RED, GREEN = 5, BLUE };
typedef enum color_e Color;
typedef enum shade_e { DARK, LIGHT } Shade;
typedef enum { ON = 1, OFF = 0 } Toggle;
enum { LOOSE_A, LOOSE_B };               /* no name at all: stays int */

typedef struct {
    Color c;
    enum color_e c2;
    Shade s;
    enum shade_e s2;
    Toggle t;
    Toggle ts[2];
    int loose;
} Paint;

Shade get_shade(enum color_e c, Toggle t);
