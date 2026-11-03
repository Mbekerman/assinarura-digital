# Servidor de retorno do AssinaPDF para o Google Cloud Run (ou qualquer host de contêiner).
# Sem dependências externas: só o Bun e o index.ts. Os segredos chegam por variáveis de ambiente.
FROM oven/bun:1.4.2-alpine
WORKDIR /app
COPY index.ts .
USER bun
ENV PORT=8080
EXPOSE 8080
CMD ["bun", "run", "index.ts"]
