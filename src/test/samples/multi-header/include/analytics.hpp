// analytics.hpp - NOT self-contained: include device_registry.hpp first (it uses Radar and Health)

// Radars among the first count with this health.
int countHealthy(const Radar* radars, int count, Health health);
