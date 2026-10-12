# Safe Tunnel Tool

Crie um projeto responsivo em português do Brasil chamado LivreNet, um painel moderno para gerenciar conexões VPN legítimas. O usuário quer acesso móvel confiável e de baixo custo, mas NÃO prometa internet grátis/ilimitada nem burle cobrança, autenticação ou controles da operadora. Deixe claro na interface que VPN não cria conectividade móvel e precisa de internet subjacente ou rede Wi-Fi e servidor autorizado.

Construa uma interface visual profissional, mobile-first, que funcione bem em Android e desktop, com:
- Dashboard com botão Conectar/Desconectar e status realista "Não conectado" por padrão. Não simule conexão real, velocidade ou tráfego; identifique claramente dados demonstrativos se houver.
- Lista de servidores de exemplo claramente rotulados "demonstração / não conectados", com país, latência não medida até teste real, e botão de testar que não inventa resultados.
- Configuração para importar/colar perfil WireGuard ou OpenVPN localmente, mascarando chaves/segredos, nunca salvar segredos em banco nem expô-los em logs. Se não houver backend VPN real, informar que o perfil foi validado apenas superficialmente e que o túnel não foi iniciado.
- Diagnóstico de conectividade com checklist: conexão subjacente, internet alcançável, servidor autorizado configurado, permissões VPN no Android (explicar que navegador web não consegue criar VPN de sistema).
- Monitor de dados local de demonstração, sem inventar contadores como reais.
- Seção "Como conseguir internet a baixo custo" com opções honestas: Wi-Fi autorizado, benefícios oficiais da operadora, plano adequado; alertar contra arquivos de configuração desconhecidos e apps que pedem credenciais.
- Acessibilidade, estados vazios, mensagens de erro úteis, navegação inferior mobile e visual de produto acabado. Use ícones simples, cores escuras azul-marinho/ciano, cartões bem espaçados.
- Não incluir qualquer payload de bypass, host/SNI de operadora, credenciais de terceiros, exploração de falhas de cobrança, nem alegar suporte a todas as operadoras/aparelhos. O projeto é um painel web/protótipo de gerenciamento, não um cliente VPN nativo e não deve apresentar botão como se conectasse de verdade.
Entregue a experiência visual completa e código organizado, com avisos claros do que exige um app Android nativo usando VpnService e um servidor VPN real.

This project was built with [Lovable](https://lovable.dev).

**Live app**: https://safe-tunnel-tool.lovable.app

## Build with Lovable

Continue developing this project in the [Lovable editor](https://lovable.dev/projects/ba07e8b7-2038-480f-963e-bab3dd56cb85).

- **Ship faster**: describe what you want to build and Lovable handles the code.
- **Stay in sync**: every change made in Lovable is committed straight to this repository.
- **Full ownership**: this code is yours. Push to `main` on GitHub and your changes sync back into Lovable, ready for your next prompt.

## Development

Prefer working locally? You need Node.js and npm — [install with nvm](https://github.com/nvm-sh/nvm#installing-and-updating).

```sh
git clone <this-repository-url>
cd <repository-name>
npm i
npm run dev
```
