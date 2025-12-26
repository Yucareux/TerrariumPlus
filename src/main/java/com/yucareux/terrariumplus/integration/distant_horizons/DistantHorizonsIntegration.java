package com.yucareux.terrariumplus.integration.distant_horizons;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.yucareux.terrariumplus.Terrarium;
import com.yucareux.terrariumplus.world.data.cover.TerrariumLandCoverSource;
import com.yucareux.terrariumplus.world.data.elevation.TerrariumElevationSource;
import com.yucareux.terrariumplus.worldgen.EarthChunkGenerator;
import com.yucareux.terrariumplus.worldgen.WaterSurfaceResolver;
import java.lang.reflect.Field;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

public class DistantHorizonsIntegration {
    private static final Logger LOGGER = Terrarium.LOGGER;

    private static boolean checkApiVersion() {
        final int apiMajor = DhApi.getApiMajorVersion();
        final int apiMinor = DhApi.getApiMinorVersion();
        final int apiPatch = DhApi.getApiPatchVersion();
        if (apiMajor < 4) {
            LOGGER.warn("Detected Distant Horizons {}, but API {}.{}.{} is too old - won't enable integration with Terrarium", DhApi.getModVersion(), apiMajor, apiMinor, apiPatch);
            return false;
        }
        LOGGER.info("Detected Distant Horizons {} (API {}.{}.{}), enabling integration with Terrarium", DhApi.getModVersion(), apiMajor, apiMinor, apiPatch);
        return true;
    }

    public static void bootstrap() {
        try {
            Class.forName("com.seibel.distanthorizons.api.DhApi");
        } catch (ClassNotFoundException e) {
            return;
        }

        if (!checkApiVersion()) {
            return;
        }
        DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(final DhApiEventParam<EventParam> param) {
                DistantHorizonsIntegration.onLevelLoad(param.value.levelWrapper);
            }
        });
    }

    private static void onLevelLoad(final IDhApiLevelWrapper levelWrapper) {
        if (levelWrapper.getWrappedMcObject() instanceof final ServerLevel level
                && level.getChunkSource().getGenerator() instanceof final EarthChunkGenerator earthGenerator
        ) {
            // Access private fields via reflection since we can't modify EarthChunkGenerator
            // This is a necessary evil for the port to work without core changes
            try {
                // accessing private static fields from EarthChunkGenerator
                Field elevationSourceField = EarthChunkGenerator.class.getDeclaredField("ELEVATION_SOURCE");
                elevationSourceField.setAccessible(true);
                TerrariumElevationSource elevationSource = (TerrariumElevationSource) elevationSourceField.get(null);

                Field landCoverSourceField = EarthChunkGenerator.class.getDeclaredField("LAND_COVER_SOURCE");
                landCoverSourceField.setAccessible(true);
                TerrariumLandCoverSource landCoverSource = (TerrariumLandCoverSource) landCoverSourceField.get(null);
                
                // accessing private field from instance
                Field waterResolverField = EarthChunkGenerator.class.getDeclaredField("waterResolver");
                waterResolverField.setAccessible(true);
                WaterSurfaceResolver waterResolver = (WaterSurfaceResolver) waterResolverField.get(earthGenerator);

                final EarthLodGenerator lodGenerator = new EarthLodGenerator(levelWrapper, earthGenerator.settings(), elevationSource, landCoverSource, waterResolver);
                final DhApiResult<Void> result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, lodGenerator);
                if (!result.success) {
                    LOGGER.warn("Failed to register Terrarium LoD generator: {}", result.message);
                }
            } catch (Exception e) {
                LOGGER.error("Failed to initialize Terrarium DH integration", e);
            }
        }
    }
}
