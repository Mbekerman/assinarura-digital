# Servidor de retorno no Google Cloud Run

O servidor `server/retorno` faz três coisas: serve o `assetlinks.json` do App Link, a página de
retorno da autorização do SafeID e a troca do código pelo token (`/v1/token`, que acrescenta o
`client_secret`). Sem ele no ar, o app abre e edita, mas não assina.

Até 03/11/2026 ele rodava na Railway. A conta foi bloqueada e o serviço saiu do ar
(`404 Application not found`). O `client_secret` ficou guardado numa conta que o titular não
controla mais, por isso a migração começa trocando esse segredo.

## Passo a passo

### 1. Painel da Safeweb

Endereço: <https://admin-safeid.safeweb.com.br/login>. O caminho abaixo foi tirado do código
público do painel; os nomes na tela podem variar um pouco.

**Trocar a URL de retorno:** Workspaces > abra o workspace da aplicação > lista **Aplicações** >
ícone de editar da AssinaPDF > campo **URL para redirecionamento** > **Salvar**. Troque a URL da
Railway pela do Cloud Run (`<url>/oauth/callback`).

**Trocar o `client_secret`:** o painel não tem opção para gerar outro segredo numa aplicação que já
existe. Ele só lista, cadastra, edita e "resgata" aplicações. O jeito de trocar sem depender do
suporte é este:

1. Em **Aplicações**, cadastre uma aplicação nova, com a URL de retorno do Cloud Run. O painel gera
   um `client_id` e um `client_secret` novos.
2. No Cloud Shell, rode `CLIENT_ID=<client_id novo> ./deploy-cloudrun.sh --novo-segredo` e digite o
   segredo novo quando ele pedir.
3. No GitHub, crie a variável `SAFEID_CLIENTID` com o `client_id` novo e gere o APK.
4. Depois de assinar um documento com o APK novo, edite a aplicação antiga no painel e mude o
   **Status** para inativa.

A outra opção é pedir ao suporte da Safeweb que gere um segredo novo para `assinapdf-rixiycui`.

O segredo antigo deve ser tratado como exposto: ficou numa conta da Railway que o titular não
controla mais.

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

1. **Safeweb:** cadastre a URI de retorno mostrada pelo script (`<url>/oauth/callback`), como no
   passo 1.
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
