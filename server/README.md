# Servidor WireGuard próprio para o LivreNet

Este modelo prepara um servidor WireGuard real em uma VPS Ubuntu/Debian que você controla. **Ele não cria hospedagem gratuitamente e não funciona apenas por estar no GitHub.** É necessário contratar/ter uma VPS com IP público, permitir UDP na porta 51820 e manter o servidor ligado.

## Requisitos

- VPS Ubuntu/Debian com acesso administrativo.
- IP público ou nome DNS apontando para a VPS.
- Docker Engine e Docker Compose instalados.
- Regra de firewall permitindo entrada UDP na porta 51820 (na VPS e no painel do provedor).

## Implantação

1. Copie a pasta `server/` para a VPS.
2. Entre nessa pasta e crie o arquivo de ambiente:

   ```sh
   cp .env.example .env
   ```

3. Edite `.env` e troque `SERVERURL` pelo IP público ou DNS da sua VPS.
4. Inicie o serviço:

   ```sh
   docker compose up -d
   ```

5. Veja os logs para confirmar que o serviço iniciou:

   ```sh
   docker compose logs --tail=100 wireguard
   ```

6. O contêiner gera as chaves e o perfil do cliente. O caminho padrão esperado é `config/peer_livrenet-android/peer_livrenet-android.conf`. Confirme o caminho e o conteúdo na VPS antes de copiar.
7. Transfira o arquivo de perfil para o seu telefone por um canal privado e importe-o no app LivreNet Android. **Nunca publique o perfil nem as chaves privadas no GitHub ou em chats.**

## Segurança e custos

- Use apenas servidor e rede que você controla ou tem autorização para utilizar.
- O tráfego passa pela VPS e pode consumir banda e gerar cobrança.
- Este arquivo não configura regras de firewall do provedor automaticamente; confirme a regra UDP 51820 no painel da VPS.
- O exemplo permite tráfego IPv4 completo pelo túnel. Não promete anonimato absoluto nem internet grátis/ilimitada.
- Para remover o servidor: `docker compose down`. Os dados e chaves permanecem na pasta `config/`; apague-os com cuidado se for descomissionar o servidor.
- Atualize imagens e sistema operacional, proteja o acesso SSH e monitore uso de banda.
