#ifndef PRICING_HPP
#define PRICING_HPP

#include <device_registry_types.hpp>
#include "../units.hpp"

namespace pricing {

struct Price {
    double perKg;
    char currency[CURRENCY_LEN];
};

// Price of a lidar. Throws std::domain_error if perKg is not positive.
double priceOf(const Lidar& lidar, Price price);

}  // namespace pricing

#endif
