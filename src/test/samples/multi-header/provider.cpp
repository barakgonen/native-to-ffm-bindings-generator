#include "device_registry.hpp"
#include "inventory.hpp"
#include "pricing/pricing.hpp"
#include "analytics.hpp"
#include <stdexcept>
#include <string>
void updateRegistry(int reqType, Camera&, Lidar&, Radar&, double, double, void*, void** outPtr, Camera*, Lidar*,
                 Radar*, int* size, double* oc, double* bc) {
    if (reqType > REQ_LAST) throw std::out_of_range("unknown reqType " + std::to_string(reqType));
    if (outPtr) *outPtr = nullptr;
    if (size) *size = 0;
    if (oc) *oc = 0;
    if (bc) *bc = 0;
}
int packCrate(Crate& crate, const Camera* cameras, int count) {
    if (count < 0) throw std::invalid_argument("count must not be negative: " + std::to_string(count));
    crate.count = 0; crate.totalWeight = 0;
    for (int i = 0; i < count; i++) { crate.count++; crate.totalWeight += cameras[i].weight; }
    if (count > 0) crate.sample = cameras[0];
    crate.state = crateState(crate);
    return crate.count;
}
CrateState crateState(const Crate& crate) {
    return crate.count == 0 ? CrateState::EMPTY : crate.count < MAX_CRATES ? CrateState::PARTIAL : CrateState::FULL;
}
namespace pricing {
double priceOf(const Lidar& lidar, Price price) {
    if (!(price.perKg > 0)) throw std::domain_error(std::string("no price per kg in ") + price.currency);
    return lidar.weight / GRAMS_PER_KG * price.perKg;
}
}
int countHealthy(const Radar* radars, int count, Health health) {
    int n = 0;
    for (int i = 0; i < count; i++) n += radars[i].health == health;
    return n;
}
