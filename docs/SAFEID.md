# Integração SafeID (Safeweb)

Tudo abaixo foi conferido na documentação oficial (`https://pscsafeweb.safewebpss.com.br/Docs/`)
e, onde indicado, com chamadas reais à API.

## Situação

| Item | Situação |
|---|---|
| Endpoints, parâmetros e campos | Conferidos na documentação oficial |
| Cadastro da aplicação (`client_id`, URI de retorno) | Conferido: `/authorize` aceita o `client_id` e o retorno da Railway |
| `client_id` + `client_secret` no `/token` | Conferido: com o segredo da Railway o PSC responde `invalid_grant` para um código falso; com um segredo errado, "Verifique o client_id e client_secret" |
| Fluxo OAuth2 com PKCE (S256) e `state` | Implementado e testado com servidor simulado |
| Retorno HTTPS por App Link, com página de reserva | Implementado e publicado (`server/retorno`) |
| Token guardado cifrado (AES-GCM, Android Keystore) | Implementado |
| Assinatura remota de **hash** (RAW, PKCS#1 v1.5) e montagem do CMS/PAdES | Implementado e testado com chave de teste |
| Verificação local antes de declarar sucesso | Implementado e testado (inclusive adulteração) |
| **Primeira assinatura real** | **Não executada**: depende da autorização do titular no app SafeID |
| **Validação no VALIDAR do ITI** | **Não executada**: depende da assinatura real |

Nenhuma parte do app simula uma assinatura.

## API usada

URL base: `https://pscsafeweb.safewebpss.com.br/Service/Microservice/OAuth/api/v0/oauth`
(homologação: host `pscsafeweb-homologacao.safewebpss.com.br`).

| Chamada | Uso no app |
|---|---|
| `GET /authorize` | `response_type=code`, `client_id`, `redirect_uri`, `state`, `scope=signature_session`, `lifetime` (segundos; máximo 7 dias para pessoa física, padrão do PSC 5 min), `code_challenge`, `code_challenge_method=S256`, `login_hint` (CPF, opcional) |
| `POST /token` | Form: `grant_type=authorization_code`, `client_id`, `client_secret`, `code`, `redirect_uri`, `code_verifier`. Feito pela função da Railway, porque exige o segredo |
| `GET /certificate-discovery` | `Authorization: Bearer`; resposta `{status, certificates:[{alias, certificate}]}` (PEM) |
| `POST /signature` | `{certificate_alias, hashes:[{id, alias, hash, hash_algorithm: "2.16.840.1.101.3.4.2.1", signature_format: "RAW"}]}`; resposta `{certificate_alias, signatures:[{id, raw_signature}]}` |

O que não é usado, e por quê:

- `/pades-signature/*`: envia o PDF inteiro ao PSC e não aceita carimbo girado. O app manda só o
  hash.
- Fluxo CA (`authorize-ca`, `pwd_authorize`, com PIN): exigiria pedir o PIN dentro do app.
- `/signature-icp`: assina com política ICP-Brasil no próprio PSC. Desnecessário: o app inclui a
  política no CMS que ele mesmo monta (veja abaixo).

## Política de assinatura (PAdES AD-RB v1.3)

O app declara a política PAdES AD-RB v1.3 da ICP-Brasil (`SignaturePolicy.PADES_AD_RB_V1_3`):

| Campo | Valor |
|---|---|
| OID | `2.16.76.1.7.1.11.1.3` |
| Resumo (SHA-256) | `23E4BE4B9B362172E4EBB0E72B86A133ECE5AAD843D8651C6E38A0BA3F08FC60` |
| URI | `http://politicas.icpbrasil.gov.br/PA_PAdES_AD_RB_v1_3.der` |

Origem e conferência:

- A lista oficial `LPA_PAdES.der` dá a AD-RB v1.3 como vigente desde 23/07/2025 (período de
  assinatura até 22/10/2037); a v1.2 foi revogada nessa data.
- O SHA-256 do arquivo da política confere com o resumo da LPA.
- O resumo embutido na assinatura é o campo `signPolicyHash` do próprio arquivo (não o SHA-256 do
  arquivo inteiro). Conferido com uma assinatura AD-RT v1.3 real aprovada pelo ITI: o atributo
  montado pelo app com os valores dela é idêntico, byte a byte (teste em `PadesCmsTest`).
- A política exige content-type, message-digest, signing-certificate-v2 e sigPolicyId, e aceita
  sha256WithRSAEncryption: é o que o app gera.
- O validador do ITI só avalia a política de assinaturas com certificado ICP-Brasil; com
  certificado de teste ele a dá como inválida até para a política da assinatura aprovada. A
  confirmação final é uma assinatura real.

Quando o ITI publicar outra versão e revogar a v1.3, os valores precisam ser atualizados.

## Como a autorização funciona no celular

1. O app abre `/authorize` numa Custom Tab. O PSC redireciona para a página `CertificateUse`, que
   mostra um QR code e um código curto e, no celular, o botão **Autorizar no SafeID** (link para
   `https://app-psc.safewebpss.com.br/qrcode-safeweb?token=...`, que abre o app SafeID).
2. O titular aprova no app SafeID (com o PIN do SafeID; o AssinaPDF nunca vê o PIN).
3. A página `CertificateUse` consulta o PSC a cada 3 s. Ao confirmar, vai para
   `https://retorno-production.up.railway.app/oauth/callback?code=...&state=...`. O código da
   página monta esse endereço com `url-join`, que junta os parâmetros com `&`.
4. O Android abre o AssinaPDF pelo App Link. Se não abrir, a página da Railway redireciona para
   `assinapdf://oauth/callback` e mostra o botão **Voltar ao AssinaPDF**.
5. O app envia código e verificador PKCE à função da Railway, recebe o token e assina.

Ponto ainda não testado: se a Custom Tab fica em segundo plano enquanto o titular está no app
SafeID e o redirecionamento por JavaScript não dispara o App Link, basta voltar à Custom Tab e
tocar em **Voltar ao AssinaPDF**.

## Validade do token

O app pede `lifetime=604800` (7 dias, o máximo para pessoa física) com escopo
`signature_session`: uma autorização vale para várias assinaturas durante a semana. O token fica
cifrado no Keystore e é apagado ao vencer; se o PSC recusá-lo antes, o app pede nova autorização.
Escolha consciente: nesse período, quem tiver o celular desbloqueado assina sem nova aprovação no
SafeID. Para reduzir, gere o APK com `safeid.lifetime` menor.

## Como testar a assinatura real

1. Cadastre o segredo `SAFEID_BROKERKEY` no GitHub e rode o CI de novo (o Release `apk-teste` é
   atualizado).
2. Instale o APK, abra um PDF, posicione os carimbos e toque em **Assinar**.
3. Autorize no SafeID. O app só mostra "Documento assinado com sucesso" se a verificação local
   confirmar a integridade e o certificado.
4. Envie o arquivo para `https://validar.iti.gov.br` e confira o resultado (Teste I).
5. Compartilhe pelo WhatsApp e valide de novo o arquivo recebido em outro aparelho (Teste J).
