#!/usr/bin/env bash
# Publica o servidor de retorno do AssinaPDF no Google Cloud Run.
# Feito para rodar no Cloud Shell (já autenticado). O client_secret do SafeID é digitado aqui,
# vai direto para o Secret Manager e não aparece na tela, no histórico nem no repositório.
#
# Uso:  ./deploy-cloudrun.sh [--novo-segredo] [--nova-chave]
#   --novo-segredo  substitui o client_secret guardado (depois de gerar outro no portal da Safeweb)
#   --nova-chave    gera outra chave do app (SAFEID_BROKERKEY); exige APK novo
# Variáveis opcionais: REGION (padrão southamerica-east1), SERVICE (padrão assinapdf-retorno),
#   PUBLIC_URL (domínio próprio já mapeado, ex.: https://assina.exemplo.com.br),
#   CLIENT_ID (client_id de uma aplicação nova cadastrada no painel da Safeweb)
set -euo pipefail
cd "$(dirname "$0")"

REGION="${REGION:-southamerica-east1}"
SERVICE="${SERVICE:-assinapdf-retorno}"
CLIENT_ID="${CLIENT_ID:-assinapdf-rixiycui}"
TOKEN_URL="https://pscsafeweb.safewebpss.com.br/Service/Microservice/OAuth/api/v0/oauth/token"
SECRET_PSC="assinapdf-psc-client-secret"
SECRET_KEY="assinapdf-broker-key"
RUNTIME_SA_NAME="assinapdf-retorno"

NOVO_SEGREDO=false; NOVA_CHAVE=false
for a in "$@"; do
  case "$a" in
    --novo-segredo) NOVO_SEGREDO=true ;;
    --nova-chave) NOVA_CHAVE=true ;;
    *) echo "Opção desconhecida: $a" >&2; exit 2 ;;
  esac
done

PROJECT="$(gcloud config get-value project 2>/dev/null)"
[ -n "$PROJECT" ] || { echo "Defina o projeto: gcloud config set project ID_DO_PROJETO" >&2; exit 1; }
echo "Projeto: $PROJECT  Região: $REGION  Serviço: $SERVICE"

if [ "$(gcloud billing projects describe "$PROJECT" --format='value(billingEnabled)' 2>/dev/null)" != "True" ]; then
  echo "O projeto $PROJECT não tem faturamento ativo. Ative em https://console.cloud.google.com/billing" >&2
  exit 1
fi

echo "Ativando APIs (Cloud Run, Cloud Build, Artifact Registry, Secret Manager)..."
gcloud services enable run.googleapis.com cloudbuild.googleapis.com artifactregistry.googleapis.com \
  secretmanager.googleapis.com --project "$PROJECT" --quiet

segredo_existe() { gcloud secrets describe "$1" --project "$PROJECT" >/dev/null 2>&1; }
gravar_segredo() { # nome, valor por stdin
  if segredo_existe "$1"; then
    gcloud secrets versions add "$1" --data-file=- --project "$PROJECT" --quiet >/dev/null
  else
    gcloud secrets create "$1" --replication-policy=automatic --data-file=- --project "$PROJECT" --quiet >/dev/null
  fi
}

if $NOVO_SEGREDO || ! segredo_existe "$SECRET_PSC"; then
  read -r -s -p "client_secret do SafeID (não aparece ao digitar): " PSC_SECRET; echo
  [ -n "$PSC_SECRET" ] || { echo "client_secret vazio." >&2; exit 1; }
  printf '%s' "$PSC_SECRET" | gravar_segredo "$SECRET_PSC"
  unset PSC_SECRET
  echo "client_secret guardado no Secret Manager ($SECRET_PSC)."
fi

CHAVE_NOVA=""
if $NOVA_CHAVE || ! segredo_existe "$SECRET_KEY"; then
  CHAVE_NOVA="$(openssl rand -base64 36 | tr -d '\n/+=' | cut -c1-43)"
  printf '%s' "$CHAVE_NOVA" | gravar_segredo "$SECRET_KEY"
fi

RUNTIME_SA="$RUNTIME_SA_NAME@$PROJECT.iam.gserviceaccount.com"
if ! gcloud iam service-accounts describe "$RUNTIME_SA" --project "$PROJECT" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$RUNTIME_SA_NAME" --display-name "AssinaPDF retorno (Cloud Run)" \
    --project "$PROJECT" --quiet
fi
# A conta do serviço lê só estes dois segredos; nenhum outro acesso ao projeto.
for s in "$SECRET_PSC" "$SECRET_KEY"; do
  gcloud secrets add-iam-policy-binding "$s" --member "serviceAccount:$RUNTIME_SA" \
    --role roles/secretmanager.secretAccessor --project "$PROJECT" --quiet >/dev/null
done

PROJECT_NUMBER="$(gcloud projects describe "$PROJECT" --format='value(projectNumber)')"
URL="${PUBLIC_URL:-https://$SERVICE-$PROJECT_NUMBER.$REGION.run.app}"
URL="${URL%/}"
REDIRECT="$URL/oauth/callback"

echo "Publicando (Cloud Build compila o contêiner; leva alguns minutos)..."
gcloud run deploy "$SERVICE" --source . --region "$REGION" --project "$PROJECT" --quiet \
  --allow-unauthenticated \
  --service-account "$RUNTIME_SA" \
  --set-secrets "PSC_CLIENT_SECRET=$SECRET_PSC:latest,BROKER_KEY=$SECRET_KEY:latest" \
  --set-env-vars "PSC_CLIENT_ID=$CLIENT_ID,PSC_TOKEN_URL=$TOKEN_URL,ALLOWED_REDIRECT_URIS=$REDIRECT" \
  --min-instances 0 --max-instances 2 --memory 256Mi --cpu 1 --concurrency 80 --timeout 60

echo "Conferindo..."
falhou=false
codigo() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
[ "$(codigo "$URL/.well-known/assetlinks.json")" = 200 ] || { echo "ERRO: assetlinks.json não respondeu 200"; falhou=true; }
[ "$(codigo "$REDIRECT")" = 200 ] || { echo "ERRO: /oauth/callback não respondeu 200"; falhou=true; }
[ "$(codigo -X POST -H 'Content-Type: application/json' -d '{}' "$URL/v1/token")" = 401 ] \
  || { echo "ERRO: /v1/token sem a chave do app deveria responder 401"; falhou=true; }
$falhou && exit 1

cat <<EOF

Servidor no ar: $URL

1. Portal da Safeweb, aplicação $CLIENT_ID: cadastre a URI de retorno
     $REDIRECT
   e remova a antiga da Railway.
2. GitHub, Settings > Secrets and variables > Actions:
     aba Variables: SAFEID_SERVERURL = $URL
EOF
if [ "$CLIENT_ID" != "assinapdf-rixiycui" ]; then
  echo "     aba Variables: SAFEID_CLIENTID = $CLIENT_ID"
fi
if [ -n "$CHAVE_NOVA" ]; then
  cat <<EOF
     aba Secrets:   SAFEID_BROKERKEY = $CHAVE_NOVA
   (a chave só aparece agora; guarde-a junto com os outros segredos)
EOF
fi
echo "3. Rode o CI de novo (Actions > Android > Run workflow) e instale o APK novo."
