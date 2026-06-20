package com.canbus.service;

import com.canbus.model.CanFrame;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CanService {

    public enum DrivingScene {
        IDLE, CITY, HIGHWAY, ACCELERATION, DECELERATION, OFF_ROAD
    }

    public static class SceneTemplate {
        private DrivingScene id;
        private String name;
        private String description;
        private double[] rpmRange;
        private double[] speedRange;
        private double[] tempRange;
        private double[] throttleRange;
        private double[] loadRange;
        private double volatility;

        public SceneTemplate(DrivingScene id, String name, String description,
                             double[] rpmRange, double[] speedRange, double[] tempRange,
                             double[] throttleRange, double[] loadRange, double volatility) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.rpmRange = rpmRange;
            this.speedRange = speedRange;
            this.tempRange = tempRange;
            this.throttleRange = throttleRange;
            this.loadRange = loadRange;
            this.volatility = volatility;
        }

        public DrivingScene getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public double[] getRpmRange() { return rpmRange; }
        public double[] getSpeedRange() { return speedRange; }
        public double[] getTempRange() { return tempRange; }
        public double[] getThrottleRange() { return throttleRange; }
        public double[] getLoadRange() { return loadRange; }
        public double getVolatility() { return volatility; }
    }

    private static final int[] MESSAGE_IDS = {0x7DF, 0x7E8, 0x7E9, 0x7EA, 0x7EB};
    private final Random random = new Random();
    private int frameCounter = 0;
    private DrivingScene currentScene = DrivingScene.IDLE;

    private final Map<DrivingScene, SceneTemplate> sceneTemplates = new LinkedHashMap<>();

    public CanService() {
        sceneTemplates.put(DrivingScene.IDLE, new SceneTemplate(
                DrivingScene.IDLE, "怠速工况", "车辆静止，发动机怠速运转",
                new double[]{650, 900}, new double[]{0, 0}, new double[]{85, 95},
                new double[]{0, 8}, new double[]{15, 30}, 0.05
        ));
        sceneTemplates.put(DrivingScene.CITY, new SceneTemplate(
                DrivingScene.CITY, "城市道路", "频繁启停，中低速行驶",
                new double[]{800, 2500}, new double[]{0, 60}, new double[]{85, 100},
                new double[]{5, 40}, new double[]{25, 60}, 0.3
        ));
        sceneTemplates.put(DrivingScene.HIGHWAY, new SceneTemplate(
                DrivingScene.HIGHWAY, "高速巡航", "稳定高速行驶，发动机平稳运转",
                new double[]{2000, 3500}, new double[]{80, 120}, new double[]{90, 105},
                new double[]{20, 35}, new double[]{40, 65}, 0.1
        ));
        sceneTemplates.put(DrivingScene.ACCELERATION, new SceneTemplate(
                DrivingScene.ACCELERATION, "急加速", "全油门加速，转速快速攀升",
                new double[]{2500, 6000}, new double[]{30, 120}, new double[]{90, 110},
                new double[]{60, 100}, new double[]{70, 100}, 0.4
        ));
        sceneTemplates.put(DrivingScene.DECELERATION, new SceneTemplate(
                DrivingScene.DECELERATION, "减速制动", "带档滑行或制动，转速下降",
                new double[]{1000, 3000}, new double[]{10, 80}, new double[]{85, 95},
                new double[]{0, 15}, new double[]{10, 30}, 0.25
        ));
        sceneTemplates.put(DrivingScene.OFF_ROAD, new SceneTemplate(
                DrivingScene.OFF_ROAD, "越野工况", "低转速大扭矩，波动剧烈",
                new double[]{1200, 4000}, new double[]{5, 40}, new double[]{90, 115},
                new double[]{30, 80}, new double[]{50, 95}, 0.5
        ));
    }

    public List<SceneTemplate> getAllSceneTemplates() {
        return new ArrayList<>(sceneTemplates.values());
    }

    public DrivingScene getCurrentScene() {
        return currentScene;
    }

    public void setCurrentScene(DrivingScene scene) {
        this.currentScene = scene;
    }

    public SceneTemplate getCurrentSceneTemplate() {
        return sceneTemplates.get(currentScene);
    }

    public List<CanFrame> generateMockFrames() {
        return generateMockFrames(currentScene);
    }

    /**
     * Generate 20 mock OBD-II CAN frames for a specific driving scene
     */
    public List<CanFrame> generateMockFrames(DrivingScene scene) {
        List<CanFrame> frames = new ArrayList<>();
        SceneTemplate template = sceneTemplates.get(scene);
        for (int i = 0; i < 20; i++) {
            frames.add(generateSingleFrame(template));
        }
        return frames;
    }

    private CanFrame generateSingleFrame() {
        return generateSingleFrame(getCurrentSceneTemplate());
    }

    private CanFrame generateSingleFrame(SceneTemplate template) {
        int arbId = MESSAGE_IDS[random.nextInt(MESSAGE_IDS.length)];

        double rpm = generateValueWithVolatility(template.getRpmRange(), template.getVolatility());
        double speed = generateValueWithVolatility(template.getSpeedRange(), template.getVolatility());
        double temp = generateValueWithVolatility(template.getTempRange(), template.getVolatility() * 0.5);
        double throttle = generateValueWithVolatility(template.getThrottleRange(), template.getVolatility());
        double load = generateValueWithVolatility(template.getLoadRange(), template.getVolatility());

        int rpmRaw = (int) Math.round(rpm / 0.25);
        int rpmLow = rpmRaw & 0xFF;
        int rpmHigh = (rpmRaw >> 8) & 0xFF;
        int speedByte = ((int) speed) & 0xFF;
        int tempByte = ((int) temp + 40) & 0xFF;
        int throttleByte = ((int) Math.round(throttle / 0.392)) & 0xFF;
        int loadByte = ((int) Math.round(load / 0.392)) & 0xFF;

        String data = String.format("%02X %02X %02X %02X %02X %02X 00 00",
                rpmLow, rpmHigh, speedByte, tempByte, throttleByte, loadByte);

        Map<String, Double> decoded = new LinkedHashMap<>();
        decoded.put("EngineRPM", Math.round(rpm * 100.0) / 100.0);
        decoded.put("VehicleSpeed", Math.round(speed * 100.0) / 100.0);
        decoded.put("CoolantTemp", Math.round(temp * 100.0) / 100.0);
        decoded.put("ThrottlePosition", Math.round(throttle * 100.0) / 100.0);
        decoded.put("EngineLoad", Math.round(load * 100.0) / 100.0);

        String direction = random.nextDouble() > 0.3 ? "RX" : "TX";

        return new CanFrame(
                "frame-" + (++frameCounter),
                System.currentTimeMillis(),
                arbId,
                8,
                data,
                decoded,
                direction
        );
    }

    private double generateValueWithVolatility(double[] range, double volatility) {
        double baseValue = range[0] + random.nextDouble() * (range[1] - range[0]);
        double rangeSpan = range[1] - range[0];
        double noise = (random.nextDouble() - 0.5) * 2 * volatility * rangeSpan;
        double result = baseValue + noise;
        result = Math.max(range[0], Math.min(range[1], result));
        return result;
    }

    /**
     * Parse DBC text and return message definitions
     */
    public Map<String, Object> parseDbc(String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> messages = new ArrayList<>();

        String[] lines = text.split("\n");
        Map<String, Object> currentMsg = null;
        List<Map<String, Object>> signals = null;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.matches("^BO_\\s+\\d+.*")) {
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 4) {
                    currentMsg = new LinkedHashMap<>();
                    currentMsg.put("id", Integer.parseInt(parts[1]));
                    String nameDlc = parts[2];
                    String name = nameDlc.endsWith(":") ? nameDlc.substring(0, nameDlc.length() - 1) : nameDlc;
                    currentMsg.put("name", name);
                    currentMsg.put("dlc", Integer.parseInt(parts[3]));
                    currentMsg.put("sender", parts.length > 4 ? parts[4] : "Unknown");
                    signals = new ArrayList<>();
                    currentMsg.put("signals", signals);
                    messages.add(currentMsg);
                }
            } else if (trimmed.matches("^SG_\\s+.*") && signals != null) {
                Map<String, Object> sig = new LinkedHashMap<>();
                String[] parts = trimmed.split("\\s+");
                if (parts.length >= 2) {
                    sig.put("name", parts[1]);
                    signals.add(sig);
                }
            } else if (trimmed.isEmpty()) {
                currentMsg = null;
                signals = null;
            }
        }

        result.put("messages", messages);
        result.put("messageCount", messages.size());
        return result;
    }

    /**
     * Decode a frame using signal definitions (simplified)
     */
    public Map<String, Double> decodeFrame(CanFrame frame) {
        return frame.getDecoded() != null ? frame.getDecoded() : new LinkedHashMap<>();
    }

    /**
     * Get bus statistics
     */
    public Map<String, Object> getStats(int totalFrames) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalFrames", totalFrames);
        stats.put("rxCount", (int) (totalFrames * 0.7));
        stats.put("txCount", (int) (totalFrames * 0.3));
        stats.put("errorCount", 0);
        stats.put("busLoad", 15 + random.nextDouble() * 30);
        stats.put("lastUpdate", System.currentTimeMillis());
        return stats;
    }
}
