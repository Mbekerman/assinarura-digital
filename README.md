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
| Assinatura PAdES montada pelo app sobre hash assinado pelo PSC | Implementado; testado com chave de teste |
| Integração SafeID real | **Pendente de credenciais e confirmação da API** ([docs/SAFEID.md](docs/SAFEID.md)) |
| Compartilhar o PDF final pelo Sharesheet | Implementado |

O APK é compilado pelo GitHub Actions (aba **Actions**, artefato `assinapdf-apk`). O ambiente
em que o código foi escrito não tinha acesso ao Google Maven, então o app Android não foi
compilado localmente; os módulos JVM foram compilados e testados localmente.

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
./gradlew :core:stamps:test :core:signing:test :verify:pdf-jvm:test :server:token-broker:test
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
server/token-broker/     Serviço intermediário mínimo para a troca do código OAuth
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

Se o PDF já tem assinaturas, as páginas não são alteradas: os carimbos entram como anotações
travadas numa revisão incremental, e a nova assinatura vem em outra revisão. Os testes confirmam
que a assinatura anterior continua íntegra. Certificação DocMDP nível 1 bloqueia a assinatura e
nível 2 bloqueia carimbos visíveis, com aviso.

### Assinatura

1. Os carimbos finais recebem o nome do titular e o emissor lidos do certificado, e o horário
   gravado no `/M` da assinatura.
2. O PDF é preparado com `/Contents` reservado (`ETSI.CAdES.detached`).
3. O app calcula o SHA-256 do ByteRange, monta os atributos assinados (content-type,
   message-digest, signing-certificate-v2 e, se configurada, a política ICP-Brasil) e envia ao
   SafeID **só o hash** desses atributos.
4. A assinatura RSA devolvida é conferida com a chave pública antes de ser embutida.
5. O arquivo é reaberto e verificado (ByteRange cobre o arquivo inteiro, hash e assinatura
   conferem, certificado é o autorizado). Só então aparece "Documento assinado com sucesso".

A verificação local não substitui a validação da cadeia ICP-Brasil e da revogação, que deve ser
feita no VALIDAR do ITI.

## Configuração

Copie `signing-config.example.properties` para `signing-config.properties` (fora do Git) e
preencha. As mesmas chaves podem vir de variáveis de ambiente ou de segredos do GitHub Actions
(`SAFEID_BASEURL`, `SAFEID_CLIENTID`, `SAFEID_TOKENMODE`, `SAFEID_BROKERURL`, `SAFEID_BROKERKEY`).

### Serviço intermediário (somente se a Safeweb exigir client_secret)

```bash
export PSC_TOKEN_URL=https://<url-do-psc>/v0/oauth/token
export PSC_CLIENT_ID=...  PSC_CLIENT_SECRET=...
export BROKER_KEY=<texto aleatório com 16+ caracteres>
export ALLOWED_REDIRECT_URIS=assinapdf://oauth/callback
./gradlew :server:token-broker:run
```

Ele escuta em `127.0.0.1:8080` e deve ficar atrás de um proxy reverso com TLS. Só troca o código
pelo token; não recebe documentos nem hashes e não registra corpos de requisição.

## Dependências e licenças

| Biblioteca | Uso | Licença |
|---|---|---|
| AndroidX, Jetpack Compose, Material 3, DataStore, Browser | Interface e armazenamento | Apache 2.0 |
| pdfbox-android 2.0.27.0 (com.tom-roush) | Leitura, carimbos e assinatura no PDF | Apache 2.0 |
| Bouncy Castle 1.86 (jdk15to18) | CMS/PAdES e verificação | Bouncy Castle (MIT) |
| OkHttp 5.5.0 | Chamadas HTTPS ao SafeID | Apache 2.0 |
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
| `server:token-broker` | 2 | Troca do código com segredo e recusas |
| `app` (unitário) | 2 | Nomes de arquivo de saída |
| `app` (instrumentado, emulador no CI) | 3 | Compartilhamento abre o editor (Teste A), onNewIntent, dez duplos toques e pinça (Testes E e G) |

Os PDFs gerados pelos testes da JVM ficam no artefato `pdfs-de-teste` do CI.

Pendentes por dependerem do SafeID real ou de aparelho físico: Testes I e J (assinatura real e
validação no ITI), o compartilhamento real a partir do WhatsApp e o Teste K completo (as falhas
de API e rede estão cobertas por testes com servidor simulado).

## Limitações conhecidas

- A assinatura real não foi exercitada; veja [docs/SAFEID.md](docs/SAFEID.md).
- Sem carimbo de tempo (PAdES B-B). PAdES-T exigiria um servidor TSA.
- PDFs com senha de abertura não são suportados. PDFs com restrição de alteração são bloqueados
  para assinatura com aviso.
- O texto do carimbo usa Helvetica (WinAnsi); caracteres fora desse conjunto viram a forma sem
  acento.
- O logotipo BKM não está no app: os arquivos do grupo Logos do design system não puderam ser
  baixados neste ambiente, e o design system proíbe redesenhá-lo. O ícone usa o símbolo casa,
  cujo desenho está no próprio design system.
