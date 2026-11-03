# Servidor de retorno no Google Cloud Run

O servidor `server/retorno` faz três coisas: serve o `assetlinks.json` do App Link, a página de
retorno da autorização do SafeID e a troca do código pelo token (`/v1/token`, que acrescenta o
`client_secret`). Sem ele no ar, o app abre e edita, mas não assina.

Até 03/11/2026 ele rodava na Railway. A conta foi bloqueada e o serviço saiu do ar
(`404 Application not found`). O `client_secret` ficou guardado numa conta que o titular não
controla mais, por isso a migração começa trocando esse segredo.

## Passo a passo

### 1. Portal da Safeweb (antes de tudo)

1. Gere um **novo `client_secret`** para a aplicação `assinapdf-rixiycui`. O antigo deve ser tratado
   como exposto.
2. Remova a URI de retorno antiga, `https://retorno-production.up.railway.app/oauth/callback`. Se
   outra pessoa registrar esse endereço na Railway, os códigos de autorização iriam para ela. Sem o
   `client_secret` e o verificador PKCE, que fica no celular, o código não serve para nada, mas não há
   motivo para manter o endereço.
3. A URI nova só existe depois do passo 2. Volte aqui ao final.

### 2. Google Cloud (Cloud Shell)

Pré-requisitos: um projeto no Google Cloud com faturamento ativo. No volume do AssinaPDF, o uso
fica na faixa gratuita do Cloud Run e do Cloud Build; o Secret Manager custa alguns centavos de
dólar por mês pelos dois segredos.

1. Abra o [Cloud Shell](https://shell.cloud.google.com) com a conta dona do projeto.
2. Rode:

   ```bash
   gcloud config set project ID_DO_PROJETO
   git clone https://github.com/Mbekerman/assinarura-digital.git
   cd assinarura-digital/server/retorno
   ./deploy-cloudrun.sh
   ```

   O repositório é privado: o `git clone` pede usuário e um token de acesso do GitHub, com
   permissão de leitura do conteúdo.

3. O script:
   - ativa Cloud Run, Cloud Build, Artifact Registry e Secret Manager;
   - pede o `client_secret` novo, sem mostrar na tela, e grava no Secret Manager;
   - gera uma chave nova do app (`BROKER_KEY`) e grava no Secret Manager;
   - cria a conta de serviço `assinapdf-retorno`, que só lê esses dois segredos;
   - publica em `southamerica-east1` (São Paulo), com escala a zero e no máximo 2 instâncias;
   - confere as três rotas e mostra o que cadastrar em seguida, inclusive a chave nova (uma única
     vez).

### 3. Depois da publicação

1. **Safeweb:** cadastre a URI de retorno mostrada pelo script (`<url>/oauth/callback`).
2. **GitHub** (Settings > Secrets and variables > Actions):
   - aba **Variables**: `SAFEID_SERVERURL` com a URL do serviço;
   - aba **Secrets**: `SAFEID_BROKERKEY` com a chave nova.
3. Rode o CI (Actions > Android > Run workflow). Ele publica o APK novo no Release `apk-teste`.
4. Instale o APK por cima do atual. A chave de assinatura do APK é a mesma, então os rascunhos e o
   histórico são mantidos. O token do SafeID precisa ser autorizado de novo.

## Manutenção

- Trocar o `client_secret`: `./deploy-cloudrun.sh --novo-segredo`.
- Trocar a chave do app: `./deploy-cloudrun.sh --nova-chave`. Depois, atualize `SAFEID_BROKERKEY`
  no GitHub e gere um APK novo. Os APKs antigos deixam de assinar.
- Logs: Cloud Run > `assinapdf-retorno` > Registros. O servidor nunca registra códigos ou tokens.

## Domínio próprio (recomendado)

A URL `*.run.app` pertence ao Google. Se o projeto mudar de provedor de novo, a URL muda, e é preciso
recadastrar o retorno no SafeID e gerar outro APK. Com um domínio seu (por exemplo
`assina.bkm.com.br`), uma próxima mudança fica só no DNS.

Para usar: mapeie o domínio para o serviço (Cloud Run > Gerenciar domínios personalizados, ou um
balanceador de carga se a região não oferecer o mapeamento). Depois rode o script com a URL pública:

```bash
PUBLIC_URL=https://assina.exemplo.com.br ./deploy-cloudrun.sh
```

Em seguida, siga de novo o passo 3, com a URL do domínio.
