// Stand-in for the MATLAB-generated implementation (sensor.cpp, sensor_initialize.cpp, ... in a real code generation
// folder), so the sample can be built and called. Runtime errors throw std::runtime_error, as MATLAB Coder's C++
// code does with run-time checks on.
#include "sensor.h"
#include "sensor_initialize.h"
#include "sensor_terminate.h"
#include <cmath>
#include <cstring>
#include <stdexcept>

namespace sensorlib {

static bool initialized = false;

void sensor_initialize() { initialized = true; }

void sensor_terminate() { initialized = false; }

void sensor(const struct0_T *in, double out[3]) {
  if (!initialized) throw std::runtime_error("sensor: call sensor_initialize first");
  if (in->weight < 0.0) throw std::runtime_error("Domain error. The weight must be nonnegative.");
  out[0] = in->weight;
  out[1] = std::hypot(in->position[0], in->position[1]);
  out[2] = static_cast<double>(in->kind);
}

double sensor_total(const double weights[1000]) {
  double total = 0.0;
  for (int i = 0; i < 1000; i++) total += weights[i];
  return total;
}

void sensor_scale(double v[3], double factor) {
  for (int i = 0; i < 3; i++) v[i] *= factor;
}

creal_T sensor_spectrum(double re, double im) {
  creal_T c;
  c.re = re * re - im * im;
  c.im = 2.0 * re * im;
  return c;
}

boolean_T sensor_valid(const struct0_T *in) { return in->id > 0 && in->weight > 0.0; }

void sensor_summary(const struct0_T sensors[4], struct1_T *summary) {
  summary->count = 0;
  summary->total = 0.0;
  int best = 0;
  for (int i = 0; i < 4; i++) {
    if (sensors[i].id == 0) continue;
    summary->count++;
    summary->total += sensors[i].weight;
    if (sensors[i].weight > sensors[best].weight) best = i;
  }
  summary->best = sensors[best];
}

void sensor_sort(const coder::array<double, 2U> &, coder::array<double, 2U> &) {}

}  // namespace sensorlib
