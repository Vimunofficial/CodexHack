const fs = require("fs");
const vm = require("vm");

const source = fs.readFileSync("static/app.js", "utf8");
const sandbox = {
  window: { APP_CONFIG: { ledCount: 5, ledNames: ["Living Room", "Kitchen", "Bedroom", "Front Door", "Garage"] } },
  document: {
    querySelector: () => ({
      addEventListener: () => {},
      classList: { add: () => {}, remove: () => {}, toggle: () => {} },
      replaceChildren: () => {},
      textContent: "",
      value: "",
    }),
    querySelectorAll: () => [],
    createElement: () => ({
      appendChild: () => {},
      querySelector: () => ({ addEventListener: () => {} }),
      classList: { add: () => {}, remove: () => {}, toggle: () => {} },
      innerHTML: "",
      textContent: "",
    }),
  },
  localStorage: { getItem: () => "", setItem: () => {} },
  EventSource: function EventSource() {},
  setInterval: () => {},
  setTimeout: () => {},
  fetch: () => Promise.resolve({ ok: true, json: () => Promise.resolve({}) }),
  TextEncoder,
  TextDecoder,
  console,
};

vm.createContext(sandbox);
vm.runInContext(source, sandbox);

const scheduled = sandbox.parseImmediateBluetoothAssistantActions("turn on all leds on 1:32 pm");
if (scheduled.length !== 0) {
  throw new Error("Timed command was incorrectly treated as immediate");
}

const immediate = sandbox.parseImmediateBluetoothAssistantActions("turn on all leds");
if (immediate.length !== 1 || immediate[0].type !== "set_all" || immediate[0].state !== true) {
  throw new Error("Immediate command was not parsed correctly");
}

console.log("app.test.js passed");
