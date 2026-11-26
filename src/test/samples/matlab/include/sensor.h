//
// File: sensor.h
//
// MATLAB Coder version            : 23.2
// C/C++ source code generated on  : 25-Nov-2026 09:12:44
//

#ifndef SENSOR_H
#define SENSOR_H

// Include Files
#include "sensor_types.h"
#include "rtwtypes.h"
#include "coder_array.h"
#include <cstddef>
#include <cstdlib>

// Function Declarations
namespace sensorlib {
extern void sensor(const struct0_T *in, double out[3]);

extern double sensor_total(const double weights[1000]);

extern void sensor_scale(double v[3], double factor);

extern creal_T sensor_spectrum(double re, double im);

extern boolean_T sensor_valid(const struct0_T *in);

extern void sensor_summary(const struct0_T sensors[4], struct1_T *summary);

extern void sensor_sort(const coder::array<double, 2U> &x, coder::array<double, 2U> &y);

} // namespace sensorlib

#endif
//
// File trailer for sensor.h
//
// [EOF]
//
