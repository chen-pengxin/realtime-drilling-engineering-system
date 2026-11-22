package com.ruoyi.web.controller.drilling;

import java.util.Map;
import java.util.Set;

/** Validation of the shared display policy stored in engineering revisions. */
final class TrajectoryVisualizationSettings {
    private static final Set<String> ROLES = Set.of("REFERENCE", "NEIGHBOR");
    private static final Set<String> TYPES = Set.of("ACTUAL", "DESIGN", "PLANNED", "PREDICTED");
    private static final Set<String> FIELDS = Set.of("color", "width", "dash", "opacity", "nodeSize");

    private TrajectoryVisualizationSettings() { }

    static void validate(Object value) {
        if (value == null) return; // Older engineering revisions inherit defaults.
        Map<?, ?> config = map(value);
        if (!(config.get("version") instanceof Number version) || version.doubleValue() != 1)
            throw new IllegalArgumentException("轨迹可视化配置格式无效");
        if(config.containsKey("grid")){var grid=map(config.get("grid"));if(grid.containsKey("color")&&!String.valueOf(grid.get("color")).matches("#[0-9a-fA-F]{6}"))throw new IllegalArgumentException("网格颜色无效");range(grid,"width",.5,5,"网格线宽须为0.5～5px");}
        if (config.containsKey("styles")) roles(map(config.get("styles")));
        if (config.containsKey("wellbores")) {
            for (var entry : map(config.get("wellbores")).entrySet()) {
                try {
                    String id = String.valueOf(entry.getKey());
                    if (!id.matches("[1-9][0-9]*") || Long.parseLong(id) <= 0) throw new NumberFormatException();
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("轨迹样式必须关联有效井眼");
                }
                roles(map(entry.getValue()));
            }
        }
    }

    private static void roles(Map<?, ?> roles) {
        for (var role : roles.entrySet()) {
            if (!ROLES.contains(String.valueOf(role.getKey()))) throw new IllegalArgumentException("轨迹井眼角色无效");
            for (var type : map(role.getValue()).entrySet()) {
                if (!TYPES.contains(String.valueOf(type.getKey()))) throw new IllegalArgumentException("轨迹类型无效");
                Map<?, ?> style = map(type.getValue());
                for (Object key : style.keySet()) if (!FIELDS.contains(String.valueOf(key)))
                    throw new IllegalArgumentException("包含不支持的轨迹样式参数");
                if (style.containsKey("color") && !String.valueOf(style.get("color")).matches("#[0-9a-fA-F]{6}"))
                    throw new IllegalArgumentException("轨迹颜色须为六位十六进制颜色");
                if (style.containsKey("dash") && !Set.of("solid", "dash", "dot", "dashdot").contains(String.valueOf(style.get("dash"))))
                    throw new IllegalArgumentException("轨迹线型无效");
                range(style, "width", .5, 8, "轨迹线宽须为 0.5～8 px");
                range(style, "opacity", .1, 1, "轨迹不透明度须为 10%～100%");
                range(style, "nodeSize", 2, 12, "设计节点大小须为 2～12 px");
            }
        }
    }

    private static void range(Map<?, ?> style, String key, double min, double max, String message) {
        if (!style.containsKey(key)) return;
        if (!(style.get(key) instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max)
            throw new IllegalArgumentException(message);
    }

    private static Map<?, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> result)) throw new IllegalArgumentException("轨迹可视化配置须为对象");
        return result;
    }
}
