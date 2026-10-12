# LivreNet — servidor WireGuard próprio

Esta pasta prepara um gateway WireGuard administrado por você. **Adicionar estes arquivos ao GitHub não liga um servidor automaticamente**: é necessário um VPS/servidor Linux público, acesso administrativo e liberar UDP 51820 no firewall e no painel do provedor.

## Requisitos

- VPS Linux 64-bit com Docker Engine e Docker Compose plugin instalados.
- IPv4 público (ou endpoint acessível) e UDP 51820 liberado no firewall/provedor.
- Não publique arquivos de configuração de clientes nem chaves privadas no GitHub.

## Preparar

1. Copie `.env.example` para `.env` no servidor e configure `SERVERURL` com o IP público ou domínio.
2. Ajuste `PEERS` para o nome de cliente desejado.
3. Inicie com `docker compose up -d`.
4. Confira os logs com `docker compose logs -f wireguard`.
5. No servidor, obtenha o perfil do cliente na pasta persistente de configuração criada pelo container, valide que o arquivo contém `[Interface]` e `[Peer]`, e transfira-o ao seu Android por um canal privado.
6. Importe o perfil no LivreNet e conecte. Nunca coloque o perfil real no repositório.

## Segurança e limitações

- O perfil de exemplo roteia IPv4 e IPv6 pelo túnel. Isso depende de encaminhamento, NAT, DNS e conectividade configurados no servidor; teste antes de usar.
- A porta UDP deve ser permitida tanto no firewall do sistema quanto no provedor.
- Faça atualizações regulares, limite o acesso SSH administrativo e proteja backups.
- Se usar IPv6 no cliente, confirme que o servidor fornece IPv6 funcional; caso contrário, remova `::/0` de `ALLOWEDIPS` no arquivo do cliente.
- Um VPS pode ter custo e limites de tráfego. Esta configuração não oferece internet grátis ou ilimitada.
- O container gera chaves localmente no servidor. Não comite `.env`, configurações de cliente ou chaves privadas.
