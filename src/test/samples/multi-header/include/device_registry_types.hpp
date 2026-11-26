// device_registry.hpp - the provider's C++ header (stand-in for the real one).
// The registry's functions have C linkage (extern "C"); they take single devices by reference and the registry's
// arrays as pointers. The functions in namespace devreg have C++ linkage. Some functions throw C++ exceptions.
#pragma once

#define DEVICE_API __attribute__((visibility("default")))

#define REGISTRY_CAPACITY 1000          // elements in each of the three arrays
#define NAME_LEN 16
#define MAX_BEAMS 5

#define REQ_RESET 0                  // updateRegistry: empty the three arrays
#define REQ_ADD   1                  // updateRegistry: store camera, lidar, radar (those with id != 0)
#define REQ_LAST  2                  // updateRegistry: copy the last stored device of each kind back

#define REGISTRY_FULL    (-1)           // add*: no empty slot left
#define REGISTRY_INVALID (-2)           // add*: device id 0

struct Point {
    double x;
    double y;
};

enum Health { GREEN, YELLOW, RED };

struct Camera {
    int id;                          // 0 = empty slot
    double weight;
    int lenses;
    char model[NAME_LEN];
};

struct Lidar {
    int id;                          // 0 = empty slot
    double weight;
    Health health;
    Point position;
    Point* origin;
    int beams[MAX_BEAMS];
    char name[NAME_LEN];
};

struct Radar {
    int id;                          // 0 = empty slot
    double weight;
    Health health;
    char site[NAME_LEN];
};