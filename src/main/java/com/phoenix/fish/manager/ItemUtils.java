package com.phoenix.fish.manager;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;

public class ItemUtils {

    public static void applyCustomModelData(ItemMeta meta, ConfigurationSection config) {
        if (!config.contains("custom-model-data"))
            return;

        CustomModelDataComponent component = meta.getCustomModelDataComponent();

        if (config.isInt("custom-model-data")) {
            int cmd = config.getInt("custom-model-data");
            if (cmd > 0)
                component.setFloats(List.of((float) cmd));
        } else if (config.isConfigurationSection("custom-model-data")) {
            ConfigurationSection cmdSec = config.getConfigurationSection("custom-model-data");
            if (cmdSec != null) {
                List<Float> floats = cmdSec.getFloatList("floats");
                if (!floats.isEmpty())
                    component.setFloats(floats);

                List<String> strings = cmdSec.getStringList("strings");
                if (!strings.isEmpty())
                    component.setStrings(strings);

                List<Boolean> flags = cmdSec.getBooleanList("flags");
                if (!flags.isEmpty())
                    component.setFlags(flags);
            }
        }

        meta.setCustomModelDataComponent(component);
    }
}