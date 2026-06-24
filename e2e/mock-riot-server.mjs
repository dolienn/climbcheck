/**
 * Local Riot API mock for e2e tests.
 *
 * The backend gets riot.base-url=http://localhost:9099/{routing}, so all requests
 * come with a routing prefix (europe / euw1 / eun1) — the mock ignores it and
 * responds deterministically: no network, no key, no rate limits.
 */
import http from 'node:http';

const PORT = Number(process.env.MOCK_PORT || 9099);

/** gameName → puuid (stored from the account-v1 request, used in matches). */
const puuidByGameName = new Map();

const LEAGUE_ENTRY = [
  {
    queueType: 'RANKED_SOLO_5x5',
    tier: 'PLATINUM',
    rank: 'I',
    leaguePoints: 52,
    wins: 22,
    losses: 20,
    hotStreak: false
  }
];

const MATCH_IDS = ['EUNE1_MOCK_1', 'EUNE1_MOCK_2', 'EUNE1_MOCK_3'];

function matchJson(matchId, puuid) {
  const champions = [
    { championName: 'Lillia', championId: 876, kills: 9, deaths: 3, assists: 12, cs: 214, lane: 'JUNGLE', role: 'NONE', win: true },
    { championName: 'Yasuo', championId: 157, kills: 2, deaths: 12, assists: 4, cs: 201, lane: 'MIDDLE', role: 'SOLO', win: false },
    { championName: 'Darius', championId: 122, kills: 5, deaths: 6, assists: 7, cs: 180, lane: 'TOP', role: 'SOLO', win: true }
  ];
  const champ = champions[Math.abs(matchId.length + puuid.length) % champions.length];
  return {
    metadata: { matchId, dataVersion: '3' },
    info: {
      queueId: 420,
      gameDuration: 1983,
      gameEndTimestamp: 1_750_000_000_000 + Number(matchId.slice(-1)) * 3_600_000,
      participants: [
        {
          puuid,
          championName: champ.championName,
          championId: champ.championId,
          win: champ.win,
          kills: champ.kills,
          deaths: champ.deaths,
          assists: champ.assists,
          totalMinionsKilled: champ.cs,
          neutralMinionsKilled: 30,
          lane: champ.lane,
          role: champ.role
        }
      ]
    }
  };
}

function send(res, status, body) {
  const json = typeof body === 'string' ? body : JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(json);
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const parts = url.pathname.split('/').filter(Boolean);
  // strip the optional routing prefix: /europe/... /euw1/... /eun1/...
  // (also works when base-url does not contain {routing})
  const knownRoutings = new Set(['europe', 'asia', 'americas', 'sea', 'kr', 'euw1', 'eun1', 'na1', 'br1', 'jp1', 'la1', 'la2', 'oc1', 'tr1', 'ru', 'ph2', 'sg2', 'th2', 'tw2', 'vn2']);
  const path = knownRoutings.has(parts[0]) ? parts.slice(1).join('/') : parts.join('/');

  if (url.pathname === '/ping') {
    send(res, 200, { status: 'UP' });
    return;
  }

  // GET /{routing}/riot/account/v1/accounts/by-riot-id/{gameName}/{tagLine}
  const accountMatch = path.match(
    /^riot\/account\/v1\/accounts\/by-riot-id\/([^/]+)\/([^/]+)$/
  );
  if (accountMatch) {
    const gameName = decodeURIComponent(accountMatch[1]);
    const tagLine = decodeURIComponent(accountMatch[2]);
    const puuid = `mock-puuid-${gameName}`;
    puuidByGameName.set(gameName, puuid);
    send(res, 200, { puuid, gameName, tagLine, profileIconId: 7 });
    return;
  }

  // GET /{routing}/lol/summoner/v4/summoners/by-puuid/{puuid}
  const summonerMatch = path.match(/^lol\/summoner\/v4\/summoners\/by-puuid\/([^/]+)$/);
  if (summonerMatch) {
    send(res, 200, {
      id: 'mock-summoner',
      accountId: 'mock-account',
      puuid: decodeURIComponent(summonerMatch[1]),
      name: 'MockSummoner',
      profileIconId: 7,
      revisionDate: 1,
      summonerLevel: 50
    });
    return;
  }

  // GET /{routing}/lol/league/v4/entries/by-puuid/{puuid}
  const leagueMatch = path.match(/^lol\/league\/v4\/entries\/by-puuid\/([^/]+)$/);
  if (leagueMatch) {
    send(res, 200, LEAGUE_ENTRY);
    return;
  }

  // GET /{routing}/lol/match/v5/matches/by-puuid/{puuid}/ids?start=0&count=N
  const idsMatch = path.match(/^lol\/match\/v5\/matches\/by-puuid\/([^/]+)\/ids$/);
  if (idsMatch) {
    const count = Number(url.searchParams.get('count') || 3);
    send(res, 200, MATCH_IDS.slice(0, Math.max(1, count)));
    return;
  }

  // GET /{routing}/lol/match/v5/matches/{matchId}
  const matchMatch = path.match(/^lol\/match\/v5\/matches\/([^/]+)$/);
  if (matchMatch) {
    const matchId = decodeURIComponent(matchMatch[1]);
    const puuid = puuidByGameName.values().next().value || 'mock-puuid-Player';
    send(res, 200, matchJson(matchId, puuid));
    return;
  }

  send(res, 404, { message: `Mock Riot: unknown endpoint ${url.pathname}` });
});

server.listen(PORT, () => {
  console.log(`[mock-riot] listening on :${PORT}`);
});
