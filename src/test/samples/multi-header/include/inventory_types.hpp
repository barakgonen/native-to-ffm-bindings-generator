#pragma once
#include "device_registry_types.hpp"
#include "units.hpp"

#define MAX_CRATES 64

enum class CrateState { EMPTY, PARTIAL, FULL };

struct Crate {
    int id;
    Camera sample;                   // a representative camera
    int count;
    double totalWeight;              // grams
    CrateState state;
};
