package com.canbus.controller;

import com.canbus.model.CanFrame;
import com.canbus.service.CanService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CanController {

    private final CanService canService;
    private int totalFrameCount = 0;

    public CanController(CanService canService) {
        this.canService = canService;
    }

    /**
     * GET /api/frames - return mock CAN frame list
     * Optional query param: scene - generate frames for specific scene
     */
    @GetMapping("/frames")
    public List<CanFrame> getFrames(
            @RequestParam(required = false) String scene) {
        List<CanFrame> frames;
        if (scene != null && !scene.isEmpty()) {
            try {
                CanService.DrivingScene drivingScene = CanService.DrivingScene.valueOf(scene.toUpperCase());
                frames = canService.generateMockFrames(drivingScene);
            } catch (IllegalArgumentException e) {
                frames = canService.generateMockFrames();
            }
        } else {
            frames = canService.generateMockFrames();
        }
        totalFrameCount += frames.size();
        return frames;
    }

    /**
     * GET /api/scenes - return all driving scene templates
     */
    @GetMapping("/scenes")
    public List<CanService.SceneTemplate> getSceneTemplates() {
        return canService.getAllSceneTemplates();
    }

    /**
     * GET /api/scenes/current - return current driving scene
     */
    @GetMapping("/scenes/current")
    public Map<String, Object> getCurrentScene() {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("scene", canService.getCurrentScene().name());
        result.put("template", canService.getCurrentSceneTemplate());
        return result;
    }

    /**
     * POST /api/scenes/current - set current driving scene
     * Request body: { "scene": "IDLE" }
     */
    @PostMapping("/scenes/current")
    public Map<String, Object> setCurrentScene(@RequestBody Map<String, String> request) {
        String sceneStr = request.get("scene");
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        try {
            CanService.DrivingScene scene = CanService.DrivingScene.valueOf(sceneStr.toUpperCase());
            canService.setCurrentScene(scene);
            result.put("success", true);
            result.put("scene", scene.name());
            result.put("template", canService.getCurrentSceneTemplate());
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("error", "Invalid scene: " + sceneStr);
        }
        return result;
    }

    /**
     * POST /api/dbc/parse - accept DBC text, return parsed messages
     */
    @PostMapping("/dbc/parse")
    public Map<String, Object> parseDbc(@RequestBody String dbcText) {
        return canService.parseDbc(dbcText);
    }

    /**
     * GET /api/stats - return bus statistics
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return canService.getStats(totalFrameCount);
    }
}
