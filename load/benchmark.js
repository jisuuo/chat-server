import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const base = __ENV.BASE_URL || 'http://localhost:18080';
const workload = __ENV.WORKLOAD || 'W2';
const hotRoom = Number(__ENV.HOT_ROOM || 1);
const beforeId = Number(__ENV.BEFORE_ID || 50000);
const period = Number(__ENV.POLL_SECONDS || 0);
const errors = new Counter('chat_errors');
const durations = {
  W1: new Trend('chat_w1_ms', true),
  W2: new Trend('chat_w2_ms', true),
  W3: new Trend('chat_w3_ms', true),
  W4: new Trend('chat_w4_ms', true),
};

export const options = {
  vus: Number(__ENV.VUS || 1),
  duration: __ENV.DURATION || '10s',
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'count'],
  noConnectionReuse: false,
};

function roomFor(n) {
  const bucket = n % 10;
  if (bucket < 5) return 1 + (Math.floor(n / 10) % 100);
  if (bucket < 8) return 101 + (Math.floor(n / 10) % 900);
  return 1001 + (Math.floor(n / 10) % 9000);
}

function params(room, operation) {
  return {
    headers: { 'X-User-Id': String(room), 'Content-Type': 'application/json' },
    tags: { operation },
    timeout: '30s',
  };
}

function record(response, operation, expected) {
  durations[operation].add(response.timings.duration);
  const ok = check(response, { [`${operation} HTTP ${expected}`]: r => r.status === expected });
  if (!ok) errors.add(1);
  return response;
}

function send(room) {
  return record(http.post(`${base}/api/rooms/${room}/messages`,
    JSON.stringify({ content: `bench-${__VU}-${__ITER}` }), params(room, 'W1')), 'W1', 201);
}

function latest(room) {
  return record(http.get(`${base}/api/rooms/${room}/messages?size=50`, params(room, 'W2')), 'W2', 200);
}

function before(room) {
  return record(http.get(`${base}/api/rooms/${room}/messages?before=${beforeId}&size=50`,
    params(room, 'W3')), 'W3', 200);
}

const cursors = {};
function poll(room) {
  if (cursors[room] === undefined) {
    const start = latest(room);
    if (start.status === 200) {
      const rows = start.json('data.messages');
      cursors[room] = rows.length ? rows[rows.length - 1].id : 0;
    } else {
      cursors[room] = 0;
    }
  }
  const response = record(http.get(`${base}/api/rooms/${room}/messages?after=${cursors[room]}&size=100`,
    params(room, 'W4')), 'W4', 200);
  if (response.status === 200) {
    const rows = response.json('data.messages');
    if (rows.length) cursors[room] = rows[rows.length - 1].id;
  }
  if (period > 0) sleep(period);
}

export default function () {
  const n = __VU * 131 + __ITER * 977;
  const room = __ENV.HOT_ONLY === '1' ? hotRoom
    : workload === 'W4' ? roomFor(__VU * 131) : roomFor(n);
  switch (workload) {
    case 'W1': send(room); break;
    case 'W2': latest(room); break;
    case 'W3': before(hotRoom); break;
    case 'W4': poll(room); break;
    case 'W5': {
      const choice = n % 10;
      if (choice === 0) send(room);
      else if (choice < 4) latest(room);
      else if (choice < 7) poll(roomFor(__VU * 131));
      else before(hotRoom);
      break;
    }
    default: throw new Error(`Unknown WORKLOAD: ${workload}`);
  }
}

export function handleSummary(data) {
  const result = { stdout: `workload=${workload} requests=${data.metrics.http_reqs?.values?.count || 0}` };
  if (__ENV.SUMMARY_PATH) result[__ENV.SUMMARY_PATH] = JSON.stringify(data);
  return result;
}
