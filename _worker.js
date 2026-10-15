/**
 * CFOptimizer Worker
 * 使用原有配置：
 *   KV 绑定：IP_KV
 *   上传密钥：UPDATE_SECRET（沿用原版，不新增密钥）
 *
 * 接口：
 *   GET  /health  健康检查
 *   GET  /        Android App 读取现有订阅（使用原有 UPDATE_SECRET 验证）
 *   GET  /sub.txt 获取订阅文本（保留原版公开访问行为）
 *   POST /update  上传/覆盖订阅（使用原有 UPDATE_SECRET 验证）
 */
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const headers = {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff"
    };
    const textHeaders = {
      ...headers,
      "Content-Type": "text/plain; charset=utf-8"
    };

    if (request.method === "GET" && url.pathname === "/health") {
      return new Response("ok", { headers });
    }

    // App 追加上传前会读取根路径，必须返回当前订阅纯文本。
    // 使用原版已有的 UPDATE_SECRET，不引入新密钥或新配置。
    if (request.method === "GET" && url.pathname === "/") {
      const auth = request.headers.get("Authorization") || "";
      if (!env.UPDATE_SECRET || auth !== `Bearer ${env.UPDATE_SECRET}`) {
        return new Response("Unauthorized", { status: 401, headers });
      }

      try {
        const text = await env.IP_KV.get("subscription.txt");
        // KV 中确实不存在订阅时，返回空文本，允许首次追加。
        // KV 读取异常会进入 catch，绝不会伪装成空订阅。
        return new Response(text === null ? "" : text, {
          status: 200,
          headers: textHeaders
        });
      } catch (error) {
        return new Response("读取订阅失败，未返回空订阅。请检查 KV 绑定 IP_KV。", {
          status: 500,
          headers: textHeaders
        });
      }
    }

    if (request.method === "GET" && url.pathname === "/sub.txt") {
      try {
        const text = await env.IP_KV.get("subscription.txt");

        if (text === null) {
          return new Response("尚未生成订阅，请先更新 IP 列表。\n", {
            status: 404,
            headers: textHeaders
          });
        }

        return new Response(text, {
          headers: textHeaders
        });
      } catch (error) {
        return new Response("读取订阅失败，请检查 KV 绑定 IP_KV。\n", {
          status: 500,
          headers: textHeaders
        });
      }
    }

    if (request.method === "POST" && url.pathname === "/update") {
      // 保留原版上传验证，不新增任何密钥变量。
      const auth = request.headers.get("Authorization") || "";

      if (
        !env.UPDATE_SECRET ||
        auth !== `Bearer ${env.UPDATE_SECRET}`
      ) {
        return new Response("Unauthorized", {
          status: 401,
          headers
        });
      }

      let body;

      try {
        body = await request.json();
      } catch {
        return new Response("Invalid JSON", {
          status: 400,
          headers
        });
      }

      const text = String(body?.text || "");
      const lines = text
        .split(/\r?\n/)
        .map(line => line.trim())
        .filter(Boolean);

      const valid = lines.filter(line =>
        /^(?:\[[0-9a-fA-F:.]+\]|(?:\d{1,3}\.){3}\d{1,3}|[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*):\d{1,5}(?:#.*)?$/.test(line)
      );

      if (valid.length === 0 || valid.length !== lines.length) {
        return new Response(
          "请提供格式正确的 IPv4/域名:端口或 [IPv6]:端口记录；旧订阅未修改。",
          { status: 400, headers }
        );
      }

      try {
        await env.IP_KV.put(
          "subscription.txt",
          valid.join("\n") + "\n"
        );
      } catch (error) {
        return new Response("保存失败，请检查 KV 绑定 IP_KV。", {
          status: 500,
          headers
        });
      }

      return new Response(
        JSON.stringify({ ok: true, count: valid.length }),
        {
          headers: {
            ...headers,
            "Content-Type": "application/json; charset=utf-8"
          }
        }
      );
    }

    return new Response("Not Found", {
      status: 404,
      headers
    });
  }
};
