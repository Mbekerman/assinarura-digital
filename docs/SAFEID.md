# Integração SafeID (Safeweb): o que foi verificado e o que falta

Este documento separa o que está implementado e testado do que depende de informação que não
pôde ser confirmada a partir do ambiente de desenvolvimento.

## Situação

| Item | Situação |
|---|---|
| Fluxo OAuth2 com PKCE (S256), `state` contra CSRF | Implementado e testado com servidor simulado |
| Autorização no mesmo aparelho, via navegador seguro (Custom Tab) | Implementado; depende de a Safeweb aceitar a URI de retorno |
| Token guardado cifrado (AES-GCM, Android Keystore) | Implementado |
| Descoberta de certificados e leitura do titular (CN ICP-Brasil, CPF) | Implementado e testado com certificado de teste |
| Assinatura remota de **hash** (formato RAW, PKCS#1 v1.5) | Implementado e testado com servidor simulado |
| Montagem do CMS/PAdES (`ETSI.CAdES.detached`, ByteRange, `signing-certificate-v2`) | Implementado e testado com chave de teste |
| Verificação local antes de declarar sucesso | Implementado e testado (inclusive adulteração) |
| Serviço intermediário para `client_secret` | Implementado e testado (`server/token-broker`) |
| **Assinatura real com o SafeID** | **Não executada**: faltam credenciais e confirmação da API |
| **Validação no VALIDAR do ITI** | **Não executada**: depende da assinatura real |

Nenhuma parte do app simula uma assinatura. Sem configuração, o botão "Assinar" explica o que
falta e o editor e a exportação de rascunho continuam funcionando.

## O que foi possível pesquisar

- A página de documentação (`https://pscsafeweb.safewebpss.com.br/Docs/`), o site da Safeweb e o
  painel de integração não puderam ser acessados daqui (bloqueio de rede do ambiente). Nenhum
  endpoint foi copiado de lá.
- Os termos de uso do SafeID descrevem que o uso por aplicações integradas exige autorização do
  titular (QR code ou código no app SafeID, com PIN) e citam o DOC-ICP-17 e o DOC-ICP-17.01.
- O DOC-ICP-17.01 define uma API comum aos PSCs da ICP-Brasil. Integradores de BirdID, VIDaaS e
  outros descrevem publicamente essa API com: autorização OAuth2 (`/v0/oauth/authorize`, escopos
  como `signature_session` e `single_signature`, PKCE), troca de código em `/v0/oauth/token`,
  `/v0/oauth/certificate-discovery` e `/v0/oauth/signature` com `hashes[]`, OID do algoritmo de
  hash e `signature_format` `RAW` ou `CMS`.
- Por isso os caminhos padrão do app seguem esse padrão, mas **todos são configuráveis** e a URL
  base não tem valor padrão.

## O que precisa ser confirmado na documentação da Safeweb

1. URL base da API do PSC Safeweb.
2. Cadastro da aplicação no painel `https://admin-safeid.safeweb.com.br/`: `client_id`, se há
   `client_secret`, e quais URIs de retorno são aceitas (o app usa `assinapdf://oauth/callback`;
   se só HTTPS for aceito, é preciso um App Link ou uma página de retorno).
3. Se a troca do código aceita cliente público só com PKCE (`safeid.tokenMode=DIRECT_PKCE`) ou
   exige `client_secret` (usar `BROKER` e publicar o `server/token-broker`).
4. Nome exato dos campos de `/signature`: o app envia `hash_algorithm` (ajustável em
   `safeid.hashAlgorithmField`) e lê `raw_signature` ou `signature`.
5. Formato do certificado em `/certificate-discovery` (o app aceita PEM ou Base64 de DER, e
   cadeia em `certificate_chain` ou `chain`).
6. Escopos, validade máxima do token (`lifetime`) e cobrança por assinatura.
7. Política de assinatura ICP-Brasil exigida (PAdES AD-RB): OID, hash e URI oficiais do ITI. O
   app só inclui a política se esses valores forem configurados; nunca usa valores inventados.

## Como testar a assinatura real

1. Preencha `signing-config.properties` (modelo em `signing-config.example.properties`).
2. Se necessário, publique o `server/token-broker` (veja o README) e configure `safeid.brokerUrl`.
3. Gere o APK, abra um PDF, posicione os carimbos e toque em "Assinar".
4. Autorize no SafeID. O app só mostra "Documento assinado com sucesso" se a verificação local
   confirmar a integridade e o certificado.
5. Envie o arquivo para `https://validar.iti.gov.br` e confira o resultado (Teste I).
6. Compartilhe pelo WhatsApp e valide de novo o arquivo recebido em outro aparelho (Teste J).
