#!/usr/bin/env node
/**
 * CampusHub AI —— 社区只读接口基线延迟测量（Phase 03）
 *
 * ============================================================================
 * 这个脚本要回答的问题
 * ============================================================================
 * "在已经有真实数据量的库上，社区那几个主要读取路径分别要多少毫秒。"
 * 它**不是**压测：并发为 1，测的是单次请求的端到端延迟分布。
 * 并发能力、热点帖子、点赞突发这些属于 Phase 09 的范围，这里刻意不碰 ——
 * 在还没有一份单请求基线之前去谈吞吐，得到的数字无法解释。
 *
 * ============================================================================
 * 为什么走 HTTP 而不是直接量 SQL
 * ============================================================================
 * 用户感知到的是「点一下要等多久」，它包含 Tomcat、安全过滤链、参数校验、
 * JSON 序列化与连接池等待。只量 SQL 会漏掉这些，而它们经常是主要成本。
 * 至于"慢在哪一层"，用 MySQL 的 EXPLAIN 与实际执行计划来回答，见
 * docs/experiments/EX-000-query-baseline.md 中的记录。
 *
 * ============================================================================
 * 用法
 * ============================================================================
 *   node scripts/bench/query-baseline.mjs
 *   node scripts/bench/query-baseline.mjs --iterations 300 --out baseline.md
 *   node scripts/bench/query-baseline.mjs --username demo01 --password '...'
 *
 * 不传用户名/口令时跳过所有需要登录的场景（会在报告里标注为「未测量」），
 * 而不是静默少几行 —— 缺了什么必须写在报告里。
 *
 * 只用 Node 内置能力（global fetch），无第三方依赖，因此在 Windows
 * 上同样可以直接运行（平台可移植性要求见 docs/00-工程规约.md §18.6）。
 */

/** 默认参数。全部可被命令行覆盖。 */
const DEFAULTS = {
  baseUrl: 'http://127.0.0.1:8080',
  /** 每个场景的测量次数 */
  iterations: 200,
  /** 预热次数：把 JIT、连接池建立、MySQL 缓冲池冷读排除在统计之外 */
  warmup: 30,
  username: process.env.CAMPHUB_BENCH_USERNAME ?? '',
  password: process.env.CAMPHUB_BENCH_PASSWORD ?? '',
  out: '',
};

/**
 * 解析命令行参数。
 *
 * @param argv process.argv 的切片
 * @returns 合并了默认值的配置
 */
function parseArgs(argv) {
  const config = { ...DEFAULTS };
  for (let index = 0; index < argv.length; index += 1) {
    const key = argv[index];
    const value = argv[index + 1];
    switch (key) {
      case '--base-url':
        config.baseUrl = value;
        index += 1;
        break;
      case '--iterations':
        config.iterations = Number.parseInt(value, 10);
        index += 1;
        break;
      case '--warmup':
        config.warmup = Number.parseInt(value, 10);
        index += 1;
        break;
      case '--username':
        config.username = value;
        index += 1;
        break;
      case '--password':
        config.password = value;
        index += 1;
        break;
      case '--out':
        config.out = value;
        index += 1;
        break;
      case '--help':
        console.log(
          [
            '用法：node scripts/bench/query-baseline.mjs [选项]',
            '',
            '  --base-url <url>     后端地址，默认 http://127.0.0.1:8080',
            '  --iterations <n>     每个场景的测量次数，默认 200',
            '  --warmup <n>         预热次数，默认 30',
            '  --username <name>    登录用登录名（演示数据为 demo01…demoNN）',
            '  --password <pwd>     登录口令；与 --username 同时提供才测量需登录的场景',
            '  --out <file>         把 Markdown 报告写入文件（同时打印到标准输出）',
            '',
            '也可用环境变量 CAMPHUB_BENCH_USERNAME / CAMPHUB_BENCH_PASSWORD。',
          ].join('\n'),
        );
        process.exit(0);
        break;
      default:
        throw new Error(`未知参数：${key}（用 --help 查看用法）`);
    }
  }
  return config;
}

/**
 * 计算分位数。
 *
 * <p>用最近秩法（nearest-rank）：取排序后第 ceil(p/100 × n) 个值。
 * 样本量只有几百时，插值法会给出一个"比任何一次真实请求都快"的数字，
 * 而这里要的恰恰是"确实发生过的最慢的那几档"。
 *
 * @param sorted 升序排列的样本
 * @param p      分位（0~100）
 * @returns 该分位的数值
 */
function percentile(sorted, p) {
  if (sorted.length === 0) {
    return Number.NaN;
  }
  const rank = Math.ceil((p / 100) * sorted.length);
  const index = Math.min(Math.max(rank - 1, 0), sorted.length - 1);
  return sorted[index];
}

/**
 * 发起一次请求并返回状态码与耗时。
 *
 * @param baseUrl 后端地址
 * @param path    以 / 开头的路径
 * @param token   访问令牌，可为空
 * @returns 状态码与耗时（毫秒）
 */
async function timedGet(baseUrl, path, token) {
  const headers = { Accept: 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const startedAt = performance.now();
  const response = await fetch(`${baseUrl}${path}`, { headers });
  // 必须读掉响应体：不读的话连接不会被复用，后续请求会不断新建连接，
  // 测出来的是"连接建立"而不是"接口本身"
  await response.arrayBuffer();
  return { status: response.status, durationMs: performance.now() - startedAt };
}

/**
 * 发一次 JSON POST。
 *
 * @param baseUrl 后端地址
 * @param path    路径
 * @param body    请求体
 * @returns 解析后的 JSON
 */
async function postJson(baseUrl, path, body) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`POST ${path} 失败：HTTP ${response.status} ${text}`);
  }
  return text ? JSON.parse(text) : null;
}

/**
 * 发一次 GET 并返回解析后的 JSON，失败即抛。
 *
 * @param baseUrl 后端地址
 * @param path    路径
 * @param token   访问令牌，可为空
 * @returns 解析后的 JSON
 */
async function fetchJson(baseUrl, path, token) {
  const headers = { Accept: 'application/json' };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  const response = await fetch(`${baseUrl}${path}`, { headers });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`GET ${path} 失败：HTTP ${response.status} ${text}`);
  }
  return text ? JSON.parse(text) : null;
}

/**
 * 从信息流里挑一条**确实有评论**的帖子，并取回它的详情与第一条顶层评论。
 *
 * <p>为什么不能直接取信息流第一条：按发布时间倒序时，最新那条很可能还没有人评论，
 * 于是"某条顶层评论的回复列表"这个场景会被静默跳过 ——
 * **报告里少一行而无人知情**，是测量脚本最不该有的行为。少测了就写"没测"，
 * 或者像这里一样先把样本找对。
 *
 * <p>为什么是"翻遍全库"而不是"翻前几页"：评论落在哪些帖子上完全由数据分布决定。
 * 第一版这里只扫了前 25 页（最新 500 帖），结果一条都没找到 ——
 * 因为那批演示数据的评论恰好集中在更早的帖子上。那次的教训是：
 * <b>"有评论的帖子会在最新的几页里"是一个关于数据的假设，而脚本不该依赖它。</b>
 * 于是改为按服务端允许的最大页大小逐页扫到找到为止；扫不动了就如实报告原因。
 *
 * @param config 配置
 * @returns 样本帖子的标识、详情、顶层评论标识与扫描页数；找不到时 found 为 false
 */
async function pickPostWithComments(config) {
  // 服务端会把超过上限的页大小截断（app.community.feed.max-page-size），
  // 直接用上限即可把请求数压到最少：2000 帖只需 40 次请求。
  const scanSize = 50;
  // 保护上限：数据集异常大（或分页返回异常）时不至于让取样跑成"无限循环"。
  // 命中它会在报告里显示，而不是悄悄按 100 页的结果下结论。
  const maxScanPages = 100;

  let scannedPages = 0;
  let hitPageLimit = false;

  for (let page = 1; page <= maxScanPages; page += 1) {
    const listing = await fetchJson(
      config.baseUrl,
      `/api/v1/community/posts?page=${page}&size=${scanSize}`,
      null,
    );
    scannedPages = page;

    const items = listing.items ?? [];
    for (const item of items) {
      // 计数为 0 的帖子不必再发一次评论列表请求
      if (!item.commentCount) {
        continue;
      }
      const comments = await fetchJson(
        config.baseUrl,
        `/api/v1/community/posts/${item.publicId}/comments?page=1&size=20`,
        null,
      );
      const firstComment = (comments.items ?? [])[0];
      if (!firstComment) {
        // 计数大于 0 却读不到顶层评论：可能是软删除或数据不一致。
        // 换下一条候选，而不是拿它当样本 —— 否则这个场景会以 404 的形式失败。
        continue;
      }
      const detail = await fetchJson(
        config.baseUrl,
        `/api/v1/community/posts/${item.publicId}`,
        null,
      );
      return {
        found: true,
        publicId: item.publicId,
        detail,
        topLevelCommentId: firstComment.publicId,
        scannedPages,
        hitPageLimit: false,
      };
    }

    if (items.length < scanSize || page * scanSize >= (listing.total ?? 0)) {
      break;
    }
    hitPageLimit = page === maxScanPages;
  }

  return { found: false, scannedPages, hitPageLimit };
}

/**
 * 测量一个场景。
 *
 * @param config   配置
 * @param scenario 场景定义
 * @param token    访问令牌，可为空
 * @returns 测量结果
 */
async function measure(config, scenario, token) {
  for (let index = 0; index < config.warmup; index += 1) {
    await timedGet(config.baseUrl, scenario.path, token);
  }

  const durations = [];
  const failures = [];
  for (let index = 0; index < config.iterations; index += 1) {
    const result = await timedGet(config.baseUrl, scenario.path, token);
    if (result.status >= 400) {
      failures.push(result.status);
      continue;
    }
    durations.push(result.durationMs);
  }

  durations.sort((left, right) => left - right);
  const total = durations.reduce((sum, value) => sum + value, 0);

  return {
    scenario,
    count: durations.length,
    failed: failures.length,
    failureStatuses: [...new Set(failures)].join(','),
    min: durations[0] ?? Number.NaN,
    p50: percentile(durations, 50),
    p90: percentile(durations, 90),
    p95: percentile(durations, 95),
    p99: percentile(durations, 99),
    max: durations[durations.length - 1] ?? Number.NaN,
    mean: durations.length > 0 ? total / durations.length : Number.NaN,
  };
}

/**
 * 格式化毫秒。
 *
 * @param value 数值
 * @returns 保留一位小数的字符串；非数值时返回占位
 */
function ms(value) {
  return Number.isFinite(value) ? value.toFixed(1) : '—';
}

/**
 * 把结果渲染成 Markdown 表格。
 *
 * @param results 测量结果列表
 * @returns Markdown 文本
 */
function renderTable(results) {
  const lines = [
    '| 场景 | 认证 | 成功/发起 | p50 | p90 | p95 | p99 | max | mean | 失败 |',
    '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |',
  ];
  for (const result of results) {
    if (result === null) {
      continue;
    }
    if (result.skipped) {
      lines.push(`| ${result.name} | ${result.auth} | 未测量 | — | — | — | — | — | — | ${result.reason} |`);
      continue;
    }
    lines.push(
      `| ${result.scenario.name} | ${result.scenario.auth} | ${result.count}/${result.count + result.failed} ` +
        `| ${ms(result.p50)} | ${ms(result.p90)} | ${ms(result.p95)} | ${ms(result.p99)} | ${ms(result.max)} ` +
        `| ${ms(result.mean)} | ${result.failed === 0 ? '0' : `${result.failed}（HTTP ${result.failureStatuses}）`} |`,
    );
  }
  return lines.join('\n');
}

/**
 * 主流程。
 */
async function main() {
  const config = parseArgs(process.argv.slice(2));

  // ---- 先确认后端活着，并抓一份"这次测的是什么数据"的事实 ----
  const health = await fetchJson(config.baseUrl, '/api/v1/system/info', null).catch(() => null);
  if (health === null) {
    throw new Error(
      `连不上 ${config.baseUrl}。请先启动后端（并确认 app.demo-seed.enabled=true 已生成数据）。`,
    );
  }

  const categories = await fetchJson(config.baseUrl, '/api/v1/community/categories', null);
  const tags = await fetchJson(config.baseUrl, '/api/v1/community/tags', null);
  const feed = await fetchJson(config.baseUrl, '/api/v1/community/posts?page=1&size=20', null);

  if (feed.total === 0) {
    throw new Error('社区里没有帖子，测量结果没有意义。请先让演示数据生成器跑一次。');
  }

  const categorySlug = categories[0]?.slug ?? '';
  const tagSlug = tags[0]?.slug ?? '';

  // 样本帖子必须挑一条**确实有评论**的，不能直接取 feed.items[0]。
  // 信息流默认按发布时间倒序，最新那条往往还没有人评论 —— 于是"某条顶层评论的回复列表"
  // 这个场景会被静默跳过：报告里少了一行，而读报告的人不知道少了什么。
  // 测量脚本最不该有的行为就是"悄悄少测一个场景"，所以这里先把样本找对。
  const sample = await pickPostWithComments(config);
  const postPublicId = sample.found ? sample.publicId : feed.items[0].publicId;
  const detail = sample.found
    ? sample.detail
    : await fetchJson(config.baseUrl, `/api/v1/community/posts/${postPublicId}`, null);
  const topLevelCommentId = sample.found ? sample.topLevelCommentId : '';

  // ---- 登录：只用来测"带登录态的读取"（列表里的 liked/favorited 需要子查询） ----
  let token = '';
  let loginNote = '';
  if (config.username && config.password) {
    try {
      const issued = await postJson(config.baseUrl, '/api/v1/auth/login', {
        identifier: config.username,
        password: config.password,
        device: 'query-baseline-benchmark',
      });
      token = issued.accessToken;
    } catch (error) {
      loginNote = `登录失败（${error.message}），需登录的场景未测量`;
    }
  } else {
    loginNote = '未提供 --username/--password，需登录的场景未测量';
  }

  const scenarios = [
    {
      name: '板块列表 `/categories`',
      path: '/api/v1/community/categories',
      auth: '匿名',
      token: '',
    },
    {
      name: '热门标签 `/tags`',
      path: '/api/v1/community/tags',
      auth: '匿名',
      token: '',
    },
    {
      name: '信息流 第 1 页（默认最新）',
      path: '/api/v1/community/posts?page=1&size=20',
      auth: '匿名',
      token: '',
    },
    {
      name: '信息流 第 1 页（最热排序）',
      path: '/api/v1/community/posts?page=1&size=20&sort=hot',
      auth: '匿名',
      token: '',
    },
    {
      name: '信息流 第 1 页（板块筛选）',
      path: `/api/v1/community/posts?page=1&size=20&category=${encodeURIComponent(categorySlug)}`,
      auth: '匿名',
      token: '',
    },
    {
      name: '信息流 第 1 页（标签筛选）',
      path: `/api/v1/community/posts?page=1&size=20&tag=${encodeURIComponent(tagSlug)}`,
      auth: '匿名',
      token: '',
    },
    {
      name: '信息流 第 10 页（深分页）',
      path: '/api/v1/community/posts?page=10&size=20',
      auth: '匿名',
      token: '',
    },
    {
      name: '帖子详情（匿名，不写浏览）',
      path: `/api/v1/community/posts/${postPublicId}`,
      auth: '匿名',
      token: '',
    },
    {
      name: '顶层评论列表',
      path: `/api/v1/community/posts/${postPublicId}/comments?page=1&size=20`,
      auth: '匿名',
      token: '',
    },
    {
      name: '信息流 第 1 页（带登录态，含 liked/favorited）',
      path: '/api/v1/community/posts?page=1&size=20',
      auth: '令牌',
      token,
    },
    {
      name: '帖子详情（带登录态，含浏览去重查询）',
      path: `/api/v1/community/posts/${postPublicId}`,
      auth: '令牌',
      token,
    },
    {
      name: '我的收藏',
      path: '/api/v1/community/me/favorites?page=1&size=20',
      auth: '令牌',
      token,
    },
  ];

  // 这个场景**无论如何都进列表**：找不到样本时以"未测量 + 原因"的形式出现在报告里，
  // 而不是从表格中消失。少一行和少一行并注明原因，是两件完全不同的事。
  scenarios.push({
    name: '某条顶层评论的回复列表',
    path: topLevelCommentId
      ? `/api/v1/community/comments/${topLevelCommentId}/replies?page=1&size=20`
      : '',
    auth: '匿名',
    token: '',
    skipReason: topLevelCommentId
      ? ''
      : `扫描信息流 ${sample.scannedPages} 页后仍找不到带评论的帖子`
        + (sample.hitPageLimit ? '（已到扫描上限，更靠后的帖子未检查）' : '（已扫完全部帖子）'),
  });

  console.log(`# 社区只读接口基线延迟（Phase 03）\n`);
  console.log(`- 后端：${config.baseUrl}（应用 ${health.application ?? '?'} ${health.version ?? '?'}，profile ${health.profiles ?? '?'}）`);
  console.log(`- 每个场景：预热 ${config.warmup} 次 + 测量 ${config.iterations} 次，并发 1`);
  console.log(`- 数据集：帖子 ${feed.total} 条；板块 ${categories.length} 个；标签 ${tags.length} 个`);
  console.log(
    `- 样本帖子：${postPublicId}（评论 ${detail.commentCount} 条、赞 ${detail.likeCount}、` +
      `收藏 ${detail.favoriteCount}、浏览 ${detail.viewCount}）`,
  );
  console.log(
    sample.found
      ? `- 样本来源：按最新排序扫描信息流 ${sample.scannedPages} 页后选中（该帖有评论，`
        + `"回复列表"场景才有测量对象）`
      : `- 样本来源：未找到带评论的帖子（扫描 ${sample.scannedPages} 页）`,
  );
  if (loginNote) {
    console.log(`- 注意：${loginNote}`);
  }
  console.log('');

  const results = [];
  for (const scenario of scenarios) {
    if (scenario.skipReason) {
      results.push({ skipped: true, name: scenario.name, auth: scenario.auth, reason: scenario.skipReason });
      continue;
    }
    if (scenario.auth === '令牌' && !scenario.token) {
      results.push({ skipped: true, name: scenario.name, auth: scenario.auth, reason: '未提供可用令牌' });
      continue;
    }
    process.stderr.write(`测量中：${scenario.name}\n`);
    results.push(await measure(config, scenario, scenario.token));
  }

  const table = renderTable(results);
  console.log(table);
  console.log('');

  const failed = results.filter((result) => !result.skipped && result.failed > 0);
  if (failed.length > 0) {
    console.error(`有 ${failed.length} 个场景出现失败请求，上述数字不完整。`);
    process.exitCode = 1;
  }

  if (config.out) {
    const { writeFileSync } = await import('node:fs');
    writeFileSync(config.out, `${table}\n`, 'utf8');
    process.stderr.write(`表格已写入 ${config.out}\n`);
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
