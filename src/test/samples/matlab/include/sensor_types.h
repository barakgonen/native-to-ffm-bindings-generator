//
// File: sensor_types.h
//
// MATLAB Coder version            : 23.2
// C/C++ source code generated on  : 25-Nov-2026 09:12:44
//

#ifndef SENSOR_TYPES_H
#define SENSOR_TYPES_H

// Include Files
#include "rtwtypes.h"

// Type Definitions
enum class SensorKind : int
{
  Temperature = 0, // Default value
  Pressure,
  Humidity
};

struct struct0_T {
  int32_T id;
  SensorKind kind;
  real_T weight;
  real_T position[2];
  boolean_T calibrated;
  char_T name[16];
};

struct struct1_T {
  int32_T count;
  struct0_T best;
  real_T total;
};

#endif
//
// File trailer for sensor_types.h
//
// [EOF]
//
