module beatgridmedia.concurrent {
    exports com.beatgridmedia.concurrent;
    exports com.beatgridmedia.concurrent.managed;
    requires spring.tx;
    requires spring.context;
    requires spring.beans;
    requires spring.boot;
    requires jakarta.annotation;
}