module com.speedment.runtime.config {
    exports com.speedment.runtime.config.trait;
    exports com.speedment.runtime.config.identifier.trait;
    exports com.speedment.runtime.config.util;
    exports com.speedment.runtime.config.identifier;
    exports com.speedment.runtime.config;
    requires com.speedment.common.invariant;
    requires com.speedment.common.lazy;
    requires com.speedment.common.mapstream;
    requires com.speedment.common.function;
}
