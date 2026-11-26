#include "inventory_types.hpp"

// Packs the first count cameras into the crate. Throws std::invalid_argument for a negative count.
int packCrate(Crate& crate, const Camera* cameras, int count);

// The crate's state, from its count.
CrateState crateState(const Crate& crate);
