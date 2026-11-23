// =============================================================================
// ЛР4 — нагрузочные сценарии vidayapi (k6).
//
// Один и тот же пользовательский сценарий используется во всех профилях:
//   логин -> просмотр ленты/видео/плейлистов -> запись (плейлист, подписка)
//   -> think time (ожидание) -> следующая сессия.
//
// Профили (переменная окружения SCENARIO):
//   ramp     — постепенное увеличение числа виртуальных пользователей;
//   stress   — baseline -> перегруз -> восстановление (три k6-сценария);
//   soak     — стабильная нагрузка; ERROR_RATE=0 / 0.1 / 0.2.
//
// Все запросы получают стабильные теги operation/scenario/phase. Сырые точки
// пишутся k6 в NDJSON (--out json) и затем разбираются scripts/analyze-loadtest.py.
// =============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://api-lt:8080';
const SCENARIO = __ENV.SCENARIO || 'smoke';
const PROFILE = __ENV.PROFILE || SCENARIO;
const ERROR_RATE = Number(__ENV.ERROR_RATE || '0');
const RUN_ID = __ENV.RUN_ID || `local-${Date.now()}`;
const PASSWORD = __ENV.LOADTEST_PASSWORD || 'VidayLoadTest!42';
const USER_COUNT = Number(__ENV.USER_COUNT || '200');
const USER_PREFIX = __ENV.USER_PREFIX || 'loaduser';

const SOAK_VUS = Number(__ENV.SOAK_VUS || '60');
const SOAK_DURATION = __ENV.SOAK_DURATION || '20m';
const RAMP_MAX_VUS = Number(__ENV.RAMP_MAX_VUS || '450');
const STRESS_BASELINE_VUS = Number(__ENV.STRESS_BASELINE_VUS || '50');
const STRESS_OVERLOAD_VUS = Number(__ENV.STRESS_OVERLOAD_VUS || '400');
const THINK_MIN_MS = Number(__ENV.THINK_MIN_MS || '1000');
const THINK_MAX_MS = Number(__ENV.THINK_MAX_MS || '2500');

const requestTimeout = __ENV.REQUEST_TIMEOUT || '30s';

const operationDuration = new Trend('operation_duration', true);
const sessionDuration = new Trend('session_duration', true);
const requestsTotal = new Counter('lt_requests_total');
const injectedErrors = new Counter('lt_injected_errors_total');
const businessErrors = new Counter('lt_business_errors_total');
const unexpectedErrors = new Counter('lt_unexpected_errors_total');

// 4xx — ожидаемые бизнес-ответы сценария; 5xx/таймауты/обрывы — отказы.
http.setResponseCallback(http.expectedStatuses(200, 201, 204, 400, 401, 403, 404, 409));

function soakScenario(tags) {
  return {
    executor: 'constant-vus',
    exec: 'session',
    vus: SOAK_VUS,
    duration: SOAK_DURATION,
    gracefulStop: '30s',
    tags,
  };
}

export const options = (() => {
  const common = {
    discardResponseBodies: false,
    noConnectionReuse: false,
    userAgent: 'viday-lr4-k6/1.0',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(75)', 'p(90)', 'p(95)', 'p(99)', 'p(99.7)'],
  };

  if (SCENARIO === 'ramp') {
    return {
      ...common,
      scenarios: {
        ramp: {
          executor: 'ramping-vus',
          exec: 'session',
          startVUs: 0,
          stages: [
            { duration: '1m', target: 10 },
            { duration: '1m', target: 25 },
            { duration: '1m', target: 50 },
            { duration: '1m', target: 75 },
            { duration: '1m', target: 100 },
            { duration: '1m', target: 150 },
            { duration: '1m', target: 200 },
            { duration: '1m', target: 275 },
            { duration: '1m', target: 350 },
            { duration: '1m', target: RAMP_MAX_VUS },
            { duration: '2m', target: RAMP_MAX_VUS },
          ],
          gracefulRampDown: '30s',
          gracefulStop: '30s',
          tags: { phase: 'ramp' },
        },
      },
    };
  }

  if (SCENARIO === 'stress') {
    return {
      ...common,
      scenarios: {
        baseline: {
          executor: 'constant-vus',
          exec: 'session',
          startTime: '0s',
          vus: STRESS_BASELINE_VUS,
          duration: '3m',
          gracefulStop: '15s',
          tags: { phase: 'baseline' },
        },
        overload: {
          executor: 'constant-vus',
          exec: 'session',
          startTime: '3m',
          vus: STRESS_OVERLOAD_VUS,
          duration: '4m',
          gracefulStop: '15s',
          tags: { phase: 'overload' },
        },
        recovery: {
          executor: 'constant-vus',
          exec: 'session',
          startTime: '7m',
          vus: STRESS_BASELINE_VUS,
          duration: '5m',
          gracefulStop: '30s',
          tags: { phase: 'recovery' },
        },
      },
    };
  }

  if (SCENARIO === 'soak' || SCENARIO === 'soak-errors') {
    const tags = { phase: ERROR_RATE > 0 ? `soak_${Math.round(ERROR_RATE * 100)}pct` : 'soak' };
    return { ...common, scenarios: { soak: soakScenario(tags) } };
  }

  // smoke: короткая проверка сценария и сидов.
  return {
    ...common,
    scenarios: {
      smoke: {
        executor: 'constant-vus',
        exec: 'session',
        vus: Number(__ENV.SMOKE_VUS || '2'),
        duration: __ENV.SMOKE_DURATION || '30s',
        gracefulStop: '10s',
        tags: { phase: 'smoke' },
      },
    },
  };
})();

export function setup() {
  const feed = http.get(`${BASE_URL}/api/videos?page=0&size=100`, { timeout: '15s' });
  if (feed.status !== 200) {
    throw new Error(`seed feed unavailable: HTTP ${feed.status}`);
  }
  const feedBody = feed.json();
  const videos = (feedBody.items || []).map((v) => ({
    id: v.id,
    ownerId: v.ownerId,
  }));
  if (videos.length === 0) {
    throw new Error('seed feed is empty: expected load-test videos');
  }

  // Сид детерминирован: публичные плейлисты имеют id 1..100 (см.
  // docker/loadtest/db/99_loadtest_seed.sql). Список через /api/playlists
  // требует JWT и в текущей реализации ожидает числовой username, поэтому
  // для воспроизводимости берём известные id прямо из сида.
  const seedPlaylistCount = Number(__ENV.SEED_PLAYLISTS || '100');
  const playlists = Array.from({ length: seedPlaylistCount }, (_, i) => i + 1);

  const users = [];
  for (let i = 1; i <= USER_COUNT; i += 1) {
    users.push(`${USER_PREFIX}${i}`);
  }

  const creators = Array.from(new Set(videos.map((v) => v.ownerId).filter((id) => id > 0)));
  if (creators.length === 0) {
    throw new Error('no creators found in seed feed');
  }

  return {
    runId: RUN_ID,
    profile: PROFILE,
    errorRate: ERROR_RATE,
    baseUrl: BASE_URL,
    videoIds: videos.map((v) => v.id),
    playlistIds: playlists,
    creatorIds: creators,
    users,
  };
}

function pick(list, seed) {
  return list[Math.abs(seed) % list.length];
}

function randomThinkMs() {
  return THINK_MIN_MS + Math.floor(Math.random() * Math.max(1, THINK_MAX_MS - THINK_MIN_MS + 1));
}

function request(method, path, body, token, operation, extraTags) {
  const headers = { 'Content-Type': 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const tags = Object.assign({ operation }, extraTags || {});
  const params = {
    headers,
    tags,
    timeout: requestTimeout,
  };
  const res = http.request(method, `${BASE_URL}${path}`, body === undefined ? null : JSON.stringify(body), params);
  const duration = res.timings ? res.timings.duration : 0;
  operationDuration.add(duration, { operation });
  requestsTotal.add(1, { operation });
  const status = res.status || 0;
  if (status === 0 || status >= 500) {
    unexpectedErrors.add(1, { operation });
  } else if (status >= 400 && !(extraTags && extraTags.injected_error === 'true')) {
    businessErrors.add(1, { operation });
  }
  if (extraTags && extraTags.injected_error === 'true') {
    injectedErrors.add(1, { operation });
  }
  check(res, {
    [`${operation} expected status`]: (r) => r.status === 0 || r.status < 500,
  }, { operation });
  return res;
}

function login(username) {
  return request('POST', '/api/auth/login', { username, password: PASSWORD }, null, 'login');
}

function extractToken(res) {
  try {
    const body = res.json();
    return body && body.token ? body.token : null;
  } catch (e) {
    return null;
  }
}

// Ровно 10 операций в сессии при ERROR_RATE=0, 10% и 20%:
//   normal = 10 - errorCount нормальных операций + errorCount ошибочных.
// При 20% (8 нормальных) пара follow/unfollow заменяется просмотром плейлиста,
// чтобы не оставлять «висящие» подписки между итерациями.
const NORMAL_ACTIONS_10 = [
  'login', 'feed', 'video', 'playlist', 'available', 'create_playlist',
  'add_content', 'delete_playlist', 'follow', 'unfollow',
];
const NORMAL_ACTIONS_9 = [
  'login', 'feed', 'video', 'available', 'create_playlist',
  'add_content', 'delete_playlist', 'follow', 'unfollow',
];
const NORMAL_ACTIONS_8 = [
  'login', 'feed', 'video', 'available', 'playlist',
  'create_playlist', 'add_content', 'delete_playlist',
];

// Только операции, которые в текущей реализации гарантированно дают 4xx.
// `GET /api/videos/99999999` возвращает 500 (EmptyResultDataAccessException) —
// это реальный баг API; его нельзя смешивать с намеренными 4xx-ошибками,
// иначе «10 % ошибок» превращаются в 2 % настоящих 5xx. Баг описан в docs/lr4.md.
const ERROR_TYPES = [
  'invalid_login',
  'unauthorized_playlist',
  'invalid_playlist',
  'not_found_user',
];

export function session(data) {
  const started = Date.now();
  const seed = (__VU * 1000003) + __ITER;
  const username = pick(data.users, seed);
  const errorCount = Math.round(ERROR_RATE * 10);
  const normalCount = 10 - errorCount;
  const normalActions = normalCount >= 10
    ? NORMAL_ACTIONS_10
    : (normalCount === 9 ? NORMAL_ACTIONS_9 : NORMAL_ACTIONS_8);

  let token = null;
  let newPlaylistId = null;
  const videoId = pick(data.videoIds, seed);
  const playlistId = data.playlistIds.length > 0 ? pick(data.playlistIds, seed >> 3) : videoId;
  const creatorId = pick(data.creatorIds, seed >> 5);

  for (let i = 0; i < normalActions.length; i += 1) {
    const action = normalActions[i];
    if (action === 'login') {
      const res = login(username);
      if (res.status === 200) {
        token = extractToken(res);
      } else {
        // Без токена продолжать пользовательскую сессию нельзя: фиксируем
        // отказ и завершаем итерацию после think time.
        break;
      }
    } else if (!token) {
      break;
    } else if (action === 'feed') {
      request('GET', '/api/videos?page=0&size=20', undefined, null, 'feed');
    } else if (action === 'video') {
      request('GET', `/api/videos/${videoId}`, undefined, null, 'video_detail');
    } else if (action === 'available') {
      request('GET', '/api/playlists/available', undefined, token, 'available_playlists');
    } else if (action === 'playlist') {
      request('GET', `/api/playlists/${playlistId}`, undefined, token, 'playlist_detail');
    } else if (action === 'create_playlist') {
      const name = `lt-${RUN_ID}-${__VU}-${__ITER}-${Date.now()}`;
      const res = request('POST', '/api/playlists', { name, accessType: 'PUBLIC' }, token, 'create_playlist');
      if (res.status === 201) {
        try {
          const body = res.json();
          newPlaylistId = body.id;
        } catch (e) {
          newPlaylistId = null;
        }
      }
    } else if (action === 'add_content') {
      if (newPlaylistId) {
        request(
          'POST',
          `/api/playlists/${newPlaylistId}/contents`,
          { contentId: videoId, position: 1 },
          token,
          'add_content',
        );
      }
    } else if (action === 'delete_playlist') {
      if (newPlaylistId) {
        request('DELETE', `/api/playlists/${newPlaylistId}`, undefined, token, 'delete_playlist');
        newPlaylistId = null;
      }
    } else if (action === 'follow') {
      const res = request('POST', `/api/users/${creatorId}/follow`, undefined, token, 'follow');
      // 200 — подписались, 409 — уже были подписаны (сид/прошлая итерация).
      if (res.status !== 200 && res.status !== 409) {
        // ничего: ошибка уже учтена
      }
    } else if (action === 'unfollow') {
      request('DELETE', `/api/users/${creatorId}/follow`, undefined, token, 'unfollow');
    }
  }

  for (let e = 0; e < errorCount; e += 1) {
    const type = ERROR_TYPES[(seed + e) % ERROR_TYPES.length];
    const tags = { injected_error: 'true' };
    if (type === 'invalid_login') {
      request('POST', '/api/auth/login', { username, password: 'definitely-wrong-password' }, null,
        'error_invalid_login', tags);
    } else if (type === 'unauthorized_playlist') {
      request('POST', '/api/playlists', { name: `unauthorized-${seed}`, accessType: 'PUBLIC' }, null,
        'error_unauthorized_playlist', tags);
    } else if (type === 'invalid_playlist') {
      request('POST', '/api/playlists', { name: '', accessType: 'PUBLIC' }, token,
        'error_invalid_playlist', tags);
    } else if (type === 'not_found_user') {
      request('GET', '/api/users/99999999', undefined, token, 'error_not_found_user', tags);
    }
  }

  sessionDuration.add(Date.now() - started);
  sleep(randomThinkMs() / 1000);
}
