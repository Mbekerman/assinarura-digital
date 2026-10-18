# AssinaPDF

Aplicativo Android nativo, de uso pessoal, para receber PDFs pelo WhatsApp, posicionar carimbos
visuais de assinatura em qualquer escala e orientação, e assinar digitalmente com o certificado
ICP-Brasil A3 em nuvem SafeID (Safeweb). A interface segue o design system BKM.

## Estado atual

| Parte | Situação |
|---|---|
| Receber PDF por compartilhamento (WhatsApp e outros) e por "Abrir com" | Implementado; teste instrumentado |
| Visualizador A4 a A0, páginas mistas, zoom, ajustar à largura e à página | Implementado |
| Carimbos proporcionais à página, mover, redimensionar, girar 360°, desfazer/refazer | Implementado; testes de unidade |
| Modo "Adicionar assinaturas" com duplo toque contínuo | Implementado; teste instrumentado |
| Exportação com carimbos vetoriais, sem rasterizar a página | Implementado; testes com renderização |
| Rascunho sem assinatura, identificado como tal | Implementado |
| Assinatura PAdES montada pelo app sobre hash assinado pelo PSC | Implementado; testado com chave de teste e com certificado real |
| Integração SafeID real | Assinaturas reais aprovadas no VALIDAR do ITI, com política AD-RB v1.3 válida ([docs/SAFEID.md](docs/SAFEID.md)) |
| Compartilhar o PDF final pelo Sharesheet | Implementado |

O APK é compilado pelo GitHub Actions (aba **Actions**, artefato `assinapdf-apk`), que também
roda todos os testes JVM, os testes unitários do app e os testes instrumentados num emulador
Android 14. O ambiente em que o código foi escrito não tinha acesso ao Google Maven; por isso o
app Android foi compilado e testado apenas no CI, e os módulos JVM também localmente.

## Instalação

1. Abra a aba **Actions** do repositório, entre na execução mais recente do workflow "Android" e
   baixe o artefato `assinapdf-apk`.
2. Use `app-release.apk` (assinado com a chave de debug se nenhuma chave de release estiver
   configurada) ou `app-debug.apk` (instala ao lado, como "AssinaPDF" com sufixo `.debug`).
3. No Android, permita a instalação de fontes desconhecidas para o app usado para abrir o APK.
4. Requisito: Android 8.0 (API 26) ou mais novo. Alvo: Android 16 (API 36).

Para compilar localmente, use o Android Studio (Ladybug ou mais novo) com JDK 17 ou 21:

```bash
./gradlew :app:assembleDebug
./gradlew :core:stamps:test :core:signing:test :verify:pdf-jvm:test
```

Sem Android SDK, o `settings.gradle.kts` ignora o módulo `:app` e compila só os módulos JVM.

## Uso

1. No WhatsApp, abra a conversa, toque no PDF, use **Compartilhar** e escolha **AssinaPDF**.
   O documento abre direto no editor.
2. Ative **Adicionar assinaturas** e dê dois toques em cada ponto onde quer um carimbo.
3. Toque num carimbo para selecioná-lo. Arraste para mover, use os cantos para redimensionar e a
   alça de cima para girar. Com dois dedos sobre o carimbo selecionado, escala e gira juntos.
4. O painel do carimbo selecionado tem tamanho em % e mm, ângulo exato, giro de 90°, duplicar,
   caber na folha, restaurar padrão e aplicar a rotação a todos.
5. Toque em **Assinar**, autorize no SafeID e use **Compartilhar** para enviar pelo WhatsApp.
6. Na tela inicial, a lista (até 30 documentos) tem três abas: **Assinados** (à espera de envio),
   **Rascunhos** e **Despachados**. Um assinado passa a despachado quando é salvo ou quando você
   escolhe um app no menu de compartilhar (o Android não informa se a mensagem foi enviada). Nas
   abas de assinados e despachados, toque e segure um PDF para entrar na seleção; toque nos outros
   para marcá-los e use **Compartilhar** para enviar todos de uma vez.
   Depois de assinar, o botão **Próximo** abre o rascunho seguinte, se houver.
7. No editor, as setas nas laterais abrem o rascunho anterior ou o próximo, na ordem da aba
   Rascunhos; os carimbos do atual ficam salvos.

## Arquitetura

```
app/                     Aplicativo Android (Kotlin, Jetpack Compose, Material 3, MVVM)
  app/                   Application, Activity única, contêiner de dependências
  ui/                    Telas Compose (início, editor, conclusão, configurações) e tema BKM
  pdf/engine/            Motor de PDF sem APIs Android: inspeção, carimbos, assinatura, verificação
  pdf/render/            Renderização com PdfRenderer em blocos, com cache LRU limitado
  sharing/               Recebimento de Intents, importação via ContentResolver, FileProvider
  signing/               Configuração e fluxo SafeID (Custom Tab, token, assinatura, verificação)
  data/                  DataStore, sessões de edição, armazenamento cifrado no Keystore
core/stamps/             Geometria das páginas e carimbos, editor, histórico (Kotlin puro)
core/signing/            Cliente da API PSC, PKCE, CMS PAdES, verificação (Kotlin puro)
verify/pdf-jvm/          Testa o motor de PDF do app na JVM, com Apache PDFBox
server/retorno/          Função da Railway: retorno HTTPS (App Link) e troca do código OAuth
```

### Modelo de coordenadas

- Cada carimbo guarda centro e largura **normalizados** em relação à página exibida (área
  CropBox ∩ MediaBox já girada pelo `/Rotate`), mais o ângulo em graus no sentido horário.
- O zoom altera apenas a transformação do visualizador; o carimbo não muda.
- Na exportação, `StampPlacement` calcula a matriz `cm` no espaço do usuário do PDF, considerando
  CropBox, rotação da página e `UserUnit`. A pré-visualização usa a mesma transformação, e os
  testes conferem que os cantos coincidem nos dois lados e que os pixels renderizados batem.
- O tamanho padrão é 20% da largura da página (configurável): 42 mm em A4, 118,8 mm em A1 e
  168,2 mm em A0.

### PDF já assinado

Se o PDF já tem assinaturas, as páginas não são alteradas: cada carimbo vira a aparência de uma
assinatura nova (widget do campo de assinatura), em revisões incrementais. Com 3 carimbos, o
documento recebe 3 assinaturas suas, uma por carimbo, todas com o mesmo token do SafeID.

Motivo: sem DocMDP declarado, o validador do ITI aplica o nível 2 às assinaturas existentes, que
permite assinar mas não anotar. Um carimbo como anotação deixava a assinatura anterior
"Indeterminada" ("modificações visuais"); como aparência da assinatura, ela continua "Aprovada"
(conferido no validar.iti.gov.br). Certificação DocMDP nível 1 bloqueia a assinatura, com aviso.
O rascunho de um PDF já assinado ainda usa anotações, porque não é assinado.

### Assinatura

1. Os carimbos finais recebem o nome do titular e o emissor lidos do certificado, e o horário
   gravado no `/M` da assinatura.
2. O PDF é preparado com `/Contents` reservado (`ETSI.CAdES.detached`).
3. O app calcula o SHA-256 do ByteRange, monta os atributos assinados (content-type,
   message-digest, signing-certificate-v2 e a política PAdES AD-RB v1.3 da ICP-Brasil) e envia ao
   SafeID **só o hash** desses atributos.
4. A assinatura RSA devolvida é conferida com a chave pública antes de ser embutida.
5. O arquivo é reaberto e verificado (ByteRange cobre o arquivo inteiro, hash e assinatura
   conferem, certificado é o autorizado). Só então aparece "Documento assinado com sucesso".

A verificação local não substitui a validação da cadeia ICP-Brasil e da revogação, que deve ser
feita no VALIDAR do ITI.

## Configuração

A integração segue a documentação oficial do SafeID (`https://pscsafeweb.safewebpss.com.br/Docs/`)
e já tem padrões no build: URL base
`https://pscsafeweb.safewebpss.com.br/Service/Microservice/OAuth/api/v0/oauth`, `client_id`
`assinapdf-rixiycui`, retorno `https://retorno-production.up.railway.app/oauth/callback`,
escopo `signature_session` e validade de 7 dias. Detalhes em [docs/SAFEID.md](docs/SAFEID.md).

O único valor que não fica no repositório é a chave de acesso ao serviço intermediário, que entra
no build pelo segredo do GitHub `SAFEID_BROKERKEY` (ou `safeid.brokerKey` em
`signing-config.properties`, fora do Git, para build local). Qualquer outro padrão pode ser
trocado do mesmo jeito (modelo em `signing-config.example.properties`).

### Serviço intermediário (Railway)

O `/token` do SafeID exige `client_secret`, que não pode ficar no APK. O arquivo
`server/retorno/index.ts` é a função Bun publicada na Railway (projeto "AssinaPDF retorno"). Ela:

- serve `/.well-known/assetlinks.json` (App Link do app assinado com a chave fixa);
- serve `/oauth/callback`, página que devolve o código ao app se o App Link não abrir sozinho;
- em `POST /v1/token`, confere o cabeçalho `X-AssinaPDF-Key` e o `redirect_uri`, acrescenta
  `client_id` e `client_secret` e chama o `/token` do SafeID. Devolve só `access_token`,
  `token_type`, `expires_in` e `scope`.

Variáveis da função: `PSC_CLIENT_ID`, `PSC_CLIENT_SECRET`, `PSC_TOKEN_URL`, `BROKER_KEY` e
`ALLOWED_REDIRECT_URIS`. Ela não recebe documentos nem hashes, não grava nada e não registra
códigos ou tokens. A chave `X-AssinaPDF-Key` fica dentro do APK: serve para conter abuso, não é um
segredo forte. Quem a tiver ainda precisaria de um código de autorização aprovado pelo titular no
app SafeID e do verificador PKCE correspondente.

## Dependências e licenças

| Biblioteca | Uso | Licença |
|---|---|---|
| AndroidX, Jetpack Compose, Material 3, DataStore, Browser | Interface e armazenamento | Apache 2.0 |
| pdfbox-android 2.0.27.0 (com.tom-roush) | Leitura, carimbos e assinatura no PDF | Apache 2.0 |
| Bouncy Castle 1.86 (jdk15to18) | CMS/PAdES e verificação | Bouncy Castle (MIT) |
| OkHttp 4.12.0 | Chamadas HTTPS ao SafeID | Apache 2.0 |
| kotlinx.coroutines, kotlinx.serialization | Assíncrono e JSON | Apache 2.0 |
| Apache PDFBox 2.0.37 | Somente testes na JVM | Apache 2.0 |

Nenhuma dependência AGPL ou comercial. A fonte Aptos do design system é licenciada pela
Microsoft e não está no APK; o app usa a sans do sistema, como prevê o próprio design system.

## Testes

| Módulo | Testes | O que cobre |
|---|---|---|
| `core:stamps` | 34 | Formatos A0–A4 (Teste B), rotações de página e carimbo, CropBox, UserUnit, colocação no PDF igual à prévia (Teste D), zoom sem efeito no tamanho (Teste G), redimensionamento proporcional (Teste C), inserção múltipla e páginas diferentes (Testes E e F), desfazer/refazer, limites |
| `core:signing` | 17 | PKCE (RFC 7636), URL de autorização, troca de código, descoberta, assinatura, erros (401, 500, JSON inválido, sem rede), CMS PAdES, adulteração, certificado vencido |
| `verify:pdf-jvm` | 9 | PDF exportado: largura física por formato, matrizes e pixels por rotação, dez carimbos, páginas mistas, conteúdo vetorial preservado (Teste H), assinatura válida, adulteração, PDF já assinado |
| `app` (unitário) | 4 | Nomes de arquivo de saída, abas e próximo rascunho |
| `app` (instrumentado, emulador no CI) | 8 | Compartilhamento abre o editor (Teste A), onNewIntent, dez duplos toques e pinça (Testes E e G), seleção de vários PDFs assinados e envio único, setas entre rascunhos, carimbo visível em PDF já assinado (pdfbox-android), aba Despachados e botão Próximo |

Os PDFs gerados pelos testes da JVM ficam no artefato `pdfs-de-teste` do CI.

Testes I e J feitos manualmente em aparelho físico: assinatura real com o SafeID e validação no
ITI (inclusive PDF com várias assinaturas). O Teste K completo depende de falhas reais do PSC; as
falhas de API e rede estão cobertas por testes com servidor simulado.

## Limitações conhecidas

- Sem carimbo de tempo (PAdES B-B). PAdES-T exigiria um servidor TSA.
- PDFs com senha de abertura não são suportados. PDFs com restrição de alteração são bloqueados
  para assinatura com aviso.
- O texto do carimbo usa Helvetica (WinAnsi); caracteres fora desse conjunto viram a forma sem
  acento.
- O logotipo BKM não está no app: os arquivos do grupo Logos do design system não puderam ser
  baixados neste ambiente, e o design system proíbe redesenhá-lo. O ícone usa o símbolo casa,
  cujo desenho está no próprio design system.
