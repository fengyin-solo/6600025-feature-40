import { defineStore } from 'pinia';
import { ref, computed } from 'vue';
import type { CanFrame, DbcMessage, BusStats, DrivingScene, DrivingSceneTemplate } from '../types';
import { parseDbc, decodeCanFrame, DEFAULT_DBC_CONTENT } from '../utils/dbc-parser';

let frameIdCounter = 0;

export const SCENE_TEMPLATES: DrivingSceneTemplate[] = [
  {
    id: 'IDLE',
    name: '怠速工况',
    description: '车辆静止，发动机怠速运转',
    rpmRange: [650, 900],
    speedRange: [0, 0],
    tempRange: [85, 95],
    throttleRange: [0, 8],
    loadRange: [15, 30],
    volatility: 0.05
  },
  {
    id: 'CITY',
    name: '城市道路',
    description: '频繁启停，中低速行驶',
    rpmRange: [800, 2500],
    speedRange: [0, 60],
    tempRange: [85, 100],
    throttleRange: [5, 40],
    loadRange: [25, 60],
    volatility: 0.3
  },
  {
    id: 'HIGHWAY',
    name: '高速巡航',
    description: '稳定高速行驶，发动机平稳运转',
    rpmRange: [2000, 3500],
    speedRange: [80, 120],
    tempRange: [90, 105],
    throttleRange: [20, 35],
    loadRange: [40, 65],
    volatility: 0.1
  },
  {
    id: 'ACCELERATION',
    name: '急加速',
    description: '全油门加速，转速快速攀升',
    rpmRange: [2500, 6000],
    speedRange: [30, 120],
    tempRange: [90, 110],
    throttleRange: [60, 100],
    loadRange: [70, 100],
    volatility: 0.4
  },
  {
    id: 'DECELERATION',
    name: '减速制动',
    description: '带档滑行或制动，转速下降',
    rpmRange: [1000, 3000],
    speedRange: [10, 80],
    tempRange: [85, 95],
    throttleRange: [0, 15],
    loadRange: [10, 30],
    volatility: 0.25
  },
  {
    id: 'OFF_ROAD',
    name: '越野工况',
    description: '低转速大扭矩，波动剧烈',
    rpmRange: [1200, 4000],
    speedRange: [5, 40],
    tempRange: [90, 115],
    throttleRange: [30, 80],
    loadRange: [50, 95],
    volatility: 0.5
  }
];

function getSceneTemplate(sceneId: DrivingScene): DrivingSceneTemplate {
  return SCENE_TEMPLATES.find(s => s.id === sceneId) || SCENE_TEMPLATES[0];
}

function generateValueWithVolatility(range: [number, number], volatility: number): number {
  const baseValue = range[0] + Math.random() * (range[1] - range[0]);
  const rangeSpan = range[1] - range[0];
  const noise = (Math.random() - 0.5) * 2 * volatility * rangeSpan;
  let result = baseValue + noise;
  result = Math.max(range[0], Math.min(range[1], result));
  return result;
}

export const useCanBusStore = defineStore('canbus', () => {
  const frames = ref<CanFrame[]>([]);
  const signals = ref<Map<string, { name: string; data: { time: number; value: number }[] }>>(new Map());
  const dbcMessages = ref<Map<number, DbcMessage>>(new Map());
  const filterId = ref('');
  const filterText = ref('');
  const isCapturing = ref(false);
  const pollInterval = ref<number | null>(null);
  const currentScene = ref<DrivingScene>('IDLE');

  const busStats = ref<BusStats>({
    totalFrames: 0,
    rxCount: 0,
    txCount: 0,
    errorCount: 0,
    busLoad: 0,
    lastUpdate: Date.now()
  });

  const filteredFrames = computed(() => {
    let result = frames.value;

    if (filterId.value.trim()) {
      const idFilter = filterId.value.trim().toLowerCase().replace(/^0x/, '');
      result = result.filter(f =>
        f.arbitrationId.toString(16).toLowerCase().includes(idFilter)
      );
    }

    if (filterText.value.trim()) {
      const textFilter = filterText.value.trim().toLowerCase();
      result = result.filter(f => {
        if (f.arbitrationId.toString(16).toLowerCase().includes(textFilter)) return true;
        if (f.data.toLowerCase().includes(textFilter)) return true;
        for (const key of Object.keys(f.decoded)) {
          if (key.toLowerCase().includes(textFilter)) return true;
        }
        return false;
      });
    }

    return result;
  });

  const busLoadPercent = computed(() => {
    return busStats.value.busLoad.toFixed(1);
  });

  function addFrame(frame: CanFrame) {
    frames.value.push(frame);
    if (frames.value.length > 500) {
      frames.value = frames.value.slice(-500);
    }

    busStats.value.totalFrames++;
    if (frame.direction === 'RX') busStats.value.rxCount++;
    else busStats.value.txCount++;
    busStats.value.lastUpdate = Date.now();

    // Update signal history
    const msgDef = dbcMessages.value.get(frame.arbitrationId);
    if (msgDef) {
      const decoded = decodeCanFrame(frame, msgDef);
      frame.decoded = decoded;
      for (const [name, value] of Object.entries(decoded)) {
        if (!signals.value.has(name)) {
          signals.value.set(name, { name, data: [] });
        }
        const sig = signals.value.get(name)!;
        sig.data.push({ time: frame.timestamp, value });
        if (sig.data.length > 100) {
          sig.data = sig.data.slice(-100);
        }
      }
    }

    // Simulate bus load (random 15-45%)
    busStats.value.busLoad = 15 + Math.random() * 30;
  }

  function clearFrames() {
    frames.value = [];
    signals.value = new Map();
    busStats.value = {
      totalFrames: 0,
      rxCount: 0,
      txCount: 0,
      errorCount: 0,
      busLoad: 0,
      lastUpdate: Date.now()
    };
    frameIdCounter = 0;
  }

  function loadMockDbc() {
    parseAndLoadDbc(DEFAULT_DBC_CONTENT);
  }

  function parseAndLoadDbc(text: string) {
    dbcMessages.value = parseDbc(text);
  }

  function generateMockFrame(scene?: DrivingScene): CanFrame {
    const effectiveScene = scene || currentScene.value;
    const template = getSceneTemplate(effectiveScene);

    const messageIds = Array.from(dbcMessages.value.keys()) as number[];
    const arbId: number = messageIds.length > 0
      ? messageIds[Math.floor(Math.random() * messageIds.length)]
      : 0x7DF;

    const msgDef = dbcMessages.value.get(arbId);

    const rpm = generateValueWithVolatility(template.rpmRange, template.volatility);
    const speed = generateValueWithVolatility(template.speedRange, template.volatility);
    const temp = generateValueWithVolatility(template.tempRange, template.volatility * 0.5);
    const throttle = generateValueWithVolatility(template.throttleRange, template.volatility);
    const load = generateValueWithVolatility(template.loadRange, template.volatility);

    const rpmRaw = Math.round(rpm / 0.25);
    const rpmLow = rpmRaw & 0xFF;
    const rpmHigh = (rpmRaw >> 8) & 0xFF;
    const speedByte = Math.round(speed) & 0xFF;
    const tempByte = (Math.round(temp) + 40) & 0xFF;
    const throttleByte = Math.round(throttle / 0.392) & 0xFF;
    const loadByte = Math.round(load / 0.392) & 0xFF;

    const dataBytes = [rpmLow, rpmHigh, speedByte, tempByte, throttleByte, loadByte, 0x00, 0x00];
    const dataHex = dataBytes.map(b => b.toString(16).padStart(2, '0').toUpperCase()).join(' ');

    const frame: CanFrame = {
      id: `frame-${++frameIdCounter}`,
      timestamp: Date.now(),
      arbitrationId: arbId,
      dlc: 8,
      data: dataHex,
      decoded: {},
      direction: Math.random() > 0.3 ? 'RX' : 'TX'
    };

    if (msgDef) {
      frame.decoded = {
        EngineRPM: Math.round(rpm * 100) / 100,
        VehicleSpeed: Math.round(speed * 100) / 100,
        CoolantTemp: Math.round(temp * 100) / 100,
        ThrottlePosition: Math.round(throttle * 100) / 100,
        EngineLoad: Math.round(load * 100) / 100
      };
    }

    return frame;
  }

  function setScene(scene: DrivingScene) {
    currentScene.value = scene;
  }

  function getCurrentSceneTemplate(): DrivingSceneTemplate {
    return getSceneTemplate(currentScene.value);
  }

  function startCapture() {
    if (isCapturing.value) return;
    isCapturing.value = true;

    // Load mock DBC if not loaded
    if (dbcMessages.value.size === 0) {
      loadMockDbc();
    }

    pollInterval.value = window.setInterval(() => {
      const frame = generateMockFrame();
      addFrame(frame);
    }, 200);
  }

  function stopCapture() {
    isCapturing.value = false;
    if (pollInterval.value !== null) {
      clearInterval(pollInterval.value);
      pollInterval.value = null;
    }
  }

  function decodeFrame(frame: CanFrame): Record<string, number> {
    const msgDef = dbcMessages.value.get(frame.arbitrationId);
    if (!msgDef) return {};
    return decodeCanFrame(frame, msgDef);
  }

  function exportFrames(): string {
    const header = 'Timestamp,Direction,CAN_ID,DLC,Data,Decoded\n';
    const rows = frames.value.map(f => {
      const decodedStr = Object.entries(f.decoded)
        .map(([k, v]) => `${k}=${v}`)
        .join('; ');
      return `${f.timestamp},${f.direction},0x${f.arbitrationId.toString(16).toUpperCase()},${f.dlc},"${f.data}","${decodedStr}"`;
    }).join('\n');
    return header + rows;
  }

  return {
    frames,
    signals,
    dbcMessages,
    filterId,
    filterText,
    busStats,
    isCapturing,
    currentScene,
    filteredFrames,
    busLoadPercent,
    addFrame,
    clearFrames,
    loadMockDbc,
    parseAndLoadDbc,
    generateMockFrame,
    startCapture,
    stopCapture,
    setScene,
    getCurrentSceneTemplate,
    decodeFrame,
    exportFrames
  };
});
