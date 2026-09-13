"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.startFeedTimer = startFeedTimer;
exports.stopFeedTimer = stopFeedTimer;
const DEFAULT_INTERVAL_MS = 45000;
let timerId = null;
function startFeedTimer(refresh, intervalMs = DEFAULT_INTERVAL_MS) {
    stopFeedTimer();
    timerId = setInterval(refresh, intervalMs);
}
function stopFeedTimer() {
    if (timerId !== null) {
        clearInterval(timerId);
        timerId = null;
    }
}
