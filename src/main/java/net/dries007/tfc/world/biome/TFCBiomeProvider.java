/*
 * Work under Copyright. Licensed under the EUPL.
 * See the project README.md and LICENSE.txt for more information.
 */

package net.dries007.tfc.world.biome;

import java.util.stream.Collectors;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryLookupCodec;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.provider.BiomeProvider;
import net.minecraft.world.gen.area.LazyArea;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.dries007.tfc.world.chunkdata.ChunkData;
import net.dries007.tfc.world.chunkdata.ChunkDataProvider;
import net.dries007.tfc.world.layer.TFCLayerUtil;

public class TFCBiomeProvider extends BiomeProvider implements ITFCBiomeProvider
{
    public static final Codec<TFCBiomeProvider> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.LONG.fieldOf("seed").forGetter(c -> c.seed),
        Codec.INT.optionalFieldOf("spawn_distance", 8_000).forGetter(TFCBiomeProvider::getSpawnDistance),
        LayerSettings.CODEC.forGetter(TFCBiomeProvider::getLayerSettings),
        ClimateSettings.CODEC.forGetter(c -> c.climateSettings),
        RegistryLookupCodec.create(Registry.BIOME_REGISTRY).forGetter(c -> c.biomeRegistry)
    ).apply(instance, TFCBiomeProvider::new));

    // Set from codec
    private final long seed;
    private final int spawnDistance;
    private final LayerSettings layerSettings;
    private final ClimateSettings climateSettings;
    private final Registry<Biome> biomeRegistry;

    private final LazyArea biomeArea;
    private ChunkDataProvider chunkDataProvider;

    public TFCBiomeProvider(long seed, int spawnDistance, LayerSettings layerSettings, ClimateSettings climateSettings, Registry<Biome> biomeRegistry)
    {
        super(TFCBiomes.getAllKeys().stream().map(biomeRegistry::getOrThrow).collect(Collectors.toList()));

        this.seed = seed;
        this.spawnDistance = spawnDistance;
        this.layerSettings = layerSettings;
        this.climateSettings = climateSettings;
        this.biomeRegistry = biomeRegistry;

        this.biomeArea = TFCLayerUtil.createOverworldBiomeLayer(seed, layerSettings).make();
    }

    public LayerSettings getLayerSettings()
    {
        return layerSettings;
    }

    public void setChunkDataProvider(ChunkDataProvider chunkDataProvider)
    {
        this.chunkDataProvider = chunkDataProvider;
    }

    @Override
    protected Codec<TFCBiomeProvider> codec()
    {
        return CODEC;
    }

    public int getSpawnDistance()
    {
        return spawnDistance;
    }

    @Override
    public TFCBiomeProvider withSeed(long seedIn)
    {
        return new TFCBiomeProvider(seedIn, spawnDistance, layerSettings, climateSettings, biomeRegistry);
    }

    /**
     * In {@link net.minecraft.world.biome.BiomeContainer}, we can see that the x, y, z positions are not absolute block coordinates.
     * Rather, since MC now samples biomes once per 4x4x4 area basis, these are not accurate for our chunk data purposes
     * So, we need to make them accurate.
     */
    @Override
    public Biome getNoiseBiome(int biomeCoordX, int biomeCoordY, int biomeCoordZ)
    {
        final ChunkPos chunkPos = new ChunkPos(biomeCoordX >> 2, biomeCoordZ >> 2);
        final BlockPos pos = chunkPos.getWorldPosition();
        final ChunkData data = chunkDataProvider.get(chunkPos, ChunkData.Status.CLIMATE);
        final BiomeVariants variants = TFCLayerUtil.getFromLayerId(biomeArea.get(biomeCoordX, biomeCoordZ));
        final BiomeTemperature temperature = calculateTemperature(data.getAverageTemp(pos));
        final BiomeRainfall rainfall = calculateRainfall(data.getRainfall(pos));
        final BiomeExtension extension = variants.get(temperature, rainfall);
        return biomeRegistry.getOrThrow(extension.getRegistryKey());
    }

    public BiomeTemperature calculateTemperature(float averageTemperature)
    {
        if (averageTemperature < climateSettings.frozenColdCutoff)
        {
            return BiomeTemperature.FROZEN;
        }
        else if (averageTemperature < climateSettings.coldNormalCutoff)
        {
            return BiomeTemperature.COLD;
        }
        else if (averageTemperature < climateSettings.normalLukewarmCutoff)
        {
            return BiomeTemperature.NORMAL;
        }
        else if (averageTemperature < climateSettings.lukewarmWarmCutoff)
        {
            return BiomeTemperature.LUKEWARM;
        }
        else
        {
            return BiomeTemperature.WARM;
        }
    }

    public BiomeRainfall calculateRainfall(float rainfall)
    {
        if (rainfall < climateSettings.aridDryCutoff)
        {
            return BiomeRainfall.ARID;
        }
        else if (rainfall < climateSettings.dryNormalCutoff)
        {
            return BiomeRainfall.DRY;
        }
        else if (rainfall < climateSettings.normalDampCutoff)
        {
            return BiomeRainfall.NORMAL;
        }
        else if (rainfall < climateSettings.dampWetCutoff)
        {
            return BiomeRainfall.DAMP;
        }
        else
        {
            return BiomeRainfall.WET;
        }
    }

    public static final class LayerSettings
    {
        private static final MapCodec<LayerSettings> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.intRange(0, 100).fieldOf("ocean_percent").forGetter(LayerSettings::getOceanPercent),
            Codec.intRange(1, 10).fieldOf("rock_layer_scale").forGetter(LayerSettings::getRockLayerScale)
        ).apply(instance, LayerSettings::new));

        private final int oceanPercent;
        private final int rockLayerScale;

        public LayerSettings()
        {
            this(45, 7);
        }

        public LayerSettings(int oceanPercent, int rockLayerScale)
        {
            this.oceanPercent = oceanPercent;
            this.rockLayerScale = rockLayerScale;
        }

        public int getOceanPercent()
        {
            return oceanPercent;
        }

        public int getRockLayerScale()
        {
            return rockLayerScale;
        }
    }

    public static final class ClimateSettings
    {
        public static final MapCodec<ClimateSettings> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.FLOAT.fieldOf("frozen_cold_cutoff").forGetter(c -> c.frozenColdCutoff),
            Codec.FLOAT.fieldOf("cold_normal_cutoff").forGetter(c -> c.coldNormalCutoff),
            Codec.FLOAT.fieldOf("normal_lukewarm_cutoff").forGetter(c -> c.normalLukewarmCutoff),
            Codec.FLOAT.fieldOf("lukewarm_warm_cutoff").forGetter(c -> c.lukewarmWarmCutoff),
            Codec.FLOAT.fieldOf("arid_dry_cutoff").forGetter(c -> c.aridDryCutoff),
            Codec.FLOAT.fieldOf("dry_normal_cutoff").forGetter(c -> c.dryNormalCutoff),
            Codec.FLOAT.fieldOf("normal_damp_cutoff").forGetter(c -> c.normalDampCutoff),
            Codec.FLOAT.fieldOf("damp_wet_cutoff").forGetter(c -> c.dampWetCutoff)
        ).apply(instance, ClimateSettings::new));

        private final float frozenColdCutoff;
        private final float coldNormalCutoff;
        private final float normalLukewarmCutoff;
        private final float lukewarmWarmCutoff;
        private final float aridDryCutoff;
        private final float dryNormalCutoff;
        private final float normalDampCutoff;
        private final float dampWetCutoff;

        public ClimateSettings()
        {
            this(-17.25f, -3.75f, 9.75f, 23.25f, 125, 200, 300, 375);
        }

        public ClimateSettings(float frozenColdCutoff, float coldNormalCutoff, float normalLukewarmCutoff, float lukewarmWarmCutoff, float aridDryCutoff, float dryNormalCutoff, float normalDampCutoff, float dampWetCutoff)
        {
            this.frozenColdCutoff = frozenColdCutoff;
            this.coldNormalCutoff = coldNormalCutoff;
            this.normalLukewarmCutoff = normalLukewarmCutoff;
            this.lukewarmWarmCutoff = lukewarmWarmCutoff;
            this.aridDryCutoff = aridDryCutoff;
            this.dryNormalCutoff = dryNormalCutoff;
            this.normalDampCutoff = normalDampCutoff;
            this.dampWetCutoff = dampWetCutoff;
        }
    }
}