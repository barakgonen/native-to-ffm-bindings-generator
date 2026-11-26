#ifndef SENSOR_INTERNAL_TYPES_H
#define SENSOR_INTERNAL_TYPES_H
#include "sensor_types.h"
#include "rtwtypes.h"
#include "coder_array.h"
struct sensorStackData { struct { coder::array<double, 2U> buf; } f0; };
#endif
