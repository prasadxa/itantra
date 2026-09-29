// Render lucide icons (react-icons/lu) to 256 px PNGs in the deck colours.
const React = require("react"), { renderToStaticMarkup } = require("react-dom/server"), sharp = require("sharp"), lu = require("react-icons/lu");
const C = { ink: "#1B2433", sa: "#EA580C", te: "#0F766E", white: "#FFFFFF", green: "#15803D", amber: "#B45309", slate: "#64748B", red: "#DC2626" };
const want = {
  mic: "LuMic", wave: "LuAudioLines", text: "LuTextCursorInput", bytes: "LuBinary", wifi: "LuWifi", wifioff: "LuWifiOff", bt: "LuBluetooth",
  speaker: "LuVolume2", phone: "LuSmartphone", cpu: "LuCpu", langs: "LuLanguages", siren: "LuSiren", pin: "LuMapPin", lock: "LuLock",
  shield: "LuShieldCheck", cloudoff: "LuCloudOff", layers: "LuLayers", zap: "LuZap", gauge: "LuGauge", memory: "LuMemoryStick",
  users: "LuUsers", ship: "LuShip", lifebuoy: "LuLifeBuoy", train: "LuTrainTrack", rupee: "LuIndianRupee", leaf: "LuLeaf",
  heart: "LuHeartHandshake", alert: "LuTriangleAlert", wrench: "LuWrench", database: "LuDatabase", github: "LuGithub", globe: "LuGlobe",
  film: "LuClapperboard", code: "LuCode", tower: "LuRadioTower", antenna: "LuRadio", check: "LuCircleCheck", clock: "LuClock3",
  flask: "LuFlaskConical", book: "LuBookOpen", brain: "LuBrainCircuit", link: "LuLink", download: "LuDownload", battery: "LuBatteryMedium",
  ear: "LuEar", eye: "LuEye", thermo: "LuThermometer", signal: "LuSignal", package: "LuPackage", android: "LuTabletSmartphone", server: "LuMonitor",
};
(async () => {
  for (const [name, comp] of Object.entries(want)) {
    const Icon = lu[comp]; if (!Icon) { console.log("missing", comp); continue; }
    for (const [cn, hex] of Object.entries(C)) {
      const svg = renderToStaticMarkup(React.createElement(Icon, { size: 256, color: hex, strokeWidth: 1.8 }));
      await sharp(Buffer.from(svg)).resize(256, 256).png().toFile(`icons/${name}-${cn}.png`);
    }
  }
  console.log("done");
})();
