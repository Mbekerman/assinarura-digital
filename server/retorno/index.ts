// AssinaPDF: retorno HTTPS da autorização SafeID e troca do código pelo token.
// - /.well-known/assetlinks.json: autoriza o app (chave fixa) a abrir este endereço direto (App Link).
// - /oauth/callback: se o Android não abrir o app sozinho, esta página devolve o código ao app.
// - /v1/token: o app envia código + verificador PKCE; aqui se adiciona o client_secret e se chama o PSC.
// Não grava nada e não registra códigos, tokens ou a URL de retorno.

import { createHash, timingSafeEqual } from "node:crypto";

const PACKAGE = "br.com.bkm.assinapdf";
const FINGERPRINTS = [
  "9A:C8:BB:93:B4:37:99:48:82:41:CF:7F:AD:EC:00:9C:08:8F:A5:C0:96:32:92:9C:EB:AC:48:47:13:8E:F4:67",
];

const assetLinks = JSON.stringify([
  {
    relation: ["delegate_permission/common.handle_all_urls"],
    target: { namespace: "android_app", package_name: PACKAGE, sha256_cert_fingerprints: FINGERPRINTS },
  },
]);

const page = `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Voltar ao AssinaPDF</title>
<style>
  :root { --marinho:#1d2e5c; --cinza:#43464b; --gelo:#e4ebf8; --branco:#ffffff; }
  * { box-sizing: border-box; }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
         background:var(--branco); color:var(--marinho);
         font-family: Aptos, "Segoe UI", system-ui, -apple-system, Roboto, Arial, sans-serif; padding:24px; }
  main { max-width:420px; width:100%; }
  .rotulo { font-size:12px; letter-spacing:.2em; text-transform:uppercase; color:var(--cinza); margin:0 0 12px; }
  h1 { font-weight:300; font-size:28px; line-height:34px; margin:0 0 12px; }
  p { font-size:16px; line-height:24px; color:var(--cinza); margin:0 0 24px; }
  a.botao { display:flex; align-items:center; justify-content:center; min-height:48px; border-radius:12px;
            background:var(--marinho); color:var(--branco); text-decoration:none; font-size:16px; }
  a.botao:focus-visible { outline:2px solid #1459c3; outline-offset:2px; }
  .erro { background:var(--gelo); border-radius:8px; padding:12px; color:var(--marinho); }
</style>
</head>
<body>
<main>
  <p class="rotulo">Assinatura digital ICP-Brasil</p>
  <h1 id="titulo">Autorização recebida</h1>
  <p id="texto">Toque no botão para voltar ao AssinaPDF e concluir a assinatura.</p>
  <a class="botao" id="voltar" href="#">Voltar ao AssinaPDF</a>
</main>
<script>
  (function () {
    var q = window.location.search || "";
    var link = "intent://oauth/callback" + q + "#Intent;scheme=assinapdf;package=${PACKAGE};end";
    var botao = document.getElementById("voltar");
    botao.setAttribute("href", link);
    if (q.indexOf("error=") >= 0) {
      document.getElementById("titulo").textContent = "Autorização não concluída";
      document.getElementById("texto").textContent = "Volte ao AssinaPDF. O documento e os carimbos continuam salvos.";
    }
    if (q.length > 1) { window.location.replace(link); }
  })();
</script>
</body>
</html>`;

const securityHeaders = {
  "Cache-Control": "no-store",
  "Referrer-Policy": "no-referrer",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};

// ---- /v1/token: troca o código de autorização pelo token, adicionando o client_secret ----
// O client_secret só existe aqui (variável PSC_CLIENT_SECRET). Nada é gravado; códigos e tokens nunca vão para o log.

const MAX_BODY = 8 * 1024;
const MAX_PER_MINUTE = 20;
const hits = new Map<string, number[]>();

function sha256(s: string): Uint8Array {
  return new Uint8Array(createHash("sha256").update(s, "utf8").digest());
}

function sameKey(given: string, expected: string): boolean {
  return expected.length > 0 && timingSafeEqual(sha256(given), sha256(expected));
}

function allow(ip: string): boolean {
  const now = Date.now();
  const list = (hits.get(ip) ?? []).filter((t) => now - t < 60_000);
  if (list.length >= MAX_PER_MINUTE) { hits.set(ip, list); return false; }
  list.push(now);
  hits.set(ip, list);
  if (hits.size > 5000) hits.clear();
  return true;
}

function jsonError(status: number, error: string, detail?: string): Response {
  return new Response(JSON.stringify(detail ? { error, detail } : { error }), {
    status,
    headers: { ...securityHeaders, "Content-Type": "application/json" },
  });
}

async function handleToken(req: Request, server: any): Promise<Response> {
  if (req.method !== "POST") return jsonError(405, "method_not_allowed");
  const env = Bun.env;
  const clientId = env.PSC_CLIENT_ID ?? "";
  const clientSecret = env.PSC_CLIENT_SECRET ?? "";
  const tokenUrl = env.PSC_TOKEN_URL ?? "";
  const allowed = (env.ALLOWED_REDIRECT_URIS ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  if (!clientId || !clientSecret || !tokenUrl.startsWith("https://") || allowed.length === 0) {
    return jsonError(503, "broker_not_configured");
  }
  if (!sameKey(req.headers.get("x-assinapdf-key") ?? "", env.BROKER_KEY ?? "")) return jsonError(401, "unauthorized");
  const ip = req.headers.get("x-real-ip") ?? req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? server?.requestIP?.(req)?.address ?? "?";
  if (!allow(ip)) return jsonError(429, "too_many_requests");

  const raw = await req.text();
  if (raw.length > MAX_BODY) return jsonError(413, "payload_too_large");
  let body: any;
  try { body = JSON.parse(raw); } catch { return jsonError(400, "invalid_json"); }
  const code = typeof body?.code === "string" ? body.code : "";
  const verifier = typeof body?.code_verifier === "string" ? body.code_verifier : "";
  const redirect = typeof body?.redirect_uri === "string" ? body.redirect_uri : "";
  if (!code || !verifier || !redirect) return jsonError(400, "missing_fields");
  if (!allowed.includes(redirect)) return jsonError(400, "redirect_not_allowed");

  const form = new URLSearchParams({
    grant_type: "authorization_code",
    client_id: clientId,
    client_secret: clientSecret,
    code,
    redirect_uri: redirect,
    code_verifier: verifier,
  });
  let upstream: Response;
  try {
    upstream = await fetch(tokenUrl, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
      body: form,
      signal: AbortSignal.timeout(30_000),
    });
  } catch {
    console.log("token: falha de rede ao chamar o PSC");
    return jsonError(502, "upstream_unreachable");
  }
  const text = await upstream.text();
  let data: any = null;
  try { data = JSON.parse(text); } catch { /* corpo não JSON */ }
  if (!upstream.ok) {
    // repassa só um código de erro curto e seguro (ex.: invalid_grant), nunca o corpo inteiro
    const e = typeof data?.error === "string" && /^[A-Za-z0-9_.-]{1,64}$/.test(data.error) ? data.error : undefined;
    console.log(`token: PSC respondeu ${upstream.status}${e ? " " + e : ""}`);
    return jsonError(upstream.status >= 400 && upstream.status < 500 ? 400 : 502, `upstream_${upstream.status}`, e);
  }
  if (!data || typeof data.access_token !== "string") return jsonError(502, "upstream_without_token");
  const out: Record<string, unknown> = {};
  for (const k of ["access_token", "token_type", "expires_in", "scope"]) if (data[k] !== undefined) out[k] = data[k];
  return new Response(JSON.stringify(out), { headers: { ...securityHeaders, "Content-Type": "application/json" } });
}

Bun.serve({
  port: Number(Bun.env.PORT ?? 3000),
  async fetch(req, server) {
    const url = new URL(req.url);
    if (url.pathname === "/.well-known/assetlinks.json") {
      return new Response(assetLinks, {
        headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=300" },
      });
    }
    if (url.pathname === "/oauth/callback") {
      return new Response(page, {
        headers: {
          ...securityHeaders,
          "Content-Type": "text/html; charset=utf-8",
          "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'",
        },
      });
    }
    if (url.pathname === "/v1/token") {
      try {
        return await handleToken(req, server);
      } catch {
        console.log("token: erro interno");
        return jsonError(502, "broker_error");
      }
    }
    if (url.pathname === "/") {
      return new Response("AssinaPDF - retorno da autorizacao SafeID", { headers: { ...securityHeaders, "Content-Type": "text/plain; charset=utf-8" } });
    }
    return new Response("Nao encontrado", { status: 404, headers: securityHeaders });
  },
});
