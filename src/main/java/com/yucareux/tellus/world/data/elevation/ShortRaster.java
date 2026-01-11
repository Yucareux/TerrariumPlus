package com.yucareux.tellus.world.data.elevation;

import java.util.Arrays;

record ShortRaster(int width, int height, short[] data) {

    static ShortRaster create(int width, int height) {
        return new ShortRaster(width, height, new short[width * height]);
    }

    static ShortRaster wrap(int width, int height, short[] data) {
        if (data.length != width * height) {
            throw new IllegalArgumentException("Invalid raster buffer");
        }
        return new ShortRaster(width, height, data);
    }


    short get(int x, int y) {
        return this.data[x + y * this.width];
    }

    void set(int x, int y, short value) {
        this.data[x + y * this.width] = value;
    }

    void fill(short value) {
        Arrays.fill(this.data, value);
    }
}
