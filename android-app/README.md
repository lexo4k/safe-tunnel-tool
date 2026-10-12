# LivreNet Android

Aplicativo Android nativo do projeto LivreNet.

## Estado atual

- Interface em português com identidade visual LivreNet.
- OpenVPN 3 Core compilado a partir do código-fonte e integrado ao APK via JNI/Rust.
- O fluxo OpenVPN seleciona um perfil `.ovpn`, valida a configuração com o núcleo nativo, solicita a autorização VPN do Android quando necessário e inicia o serviço VPN interno. Não deve abrir a tela de compartilhamento nem depender de um cliente OpenVPN externo.
- Serviço Android `VpnService` com callbacks para endereços, rotas, DNS, MTU, proteção de sockets, estabelecimento e encerramento do túnel.
- A compilação do APK de depuração e do núcleo OpenVPN passou no GitHub Actions no commit `23164ef16e003663ac44b9dbf024a19f8898b034`.
- WireGuard continua disponível como alternativa integrada para perfis `.conf` válidos.
- Descoberta de servidores públicos VPN Gate e leitura dos perfis `.ovpn` publicados pelo serviço.

## O que foi verificado e o que falta

**Verificado:** compilação nativa do OpenVPN para Android (arm64-v8a, armeabi-v7a e x86_64), compilação Gradle do APK e publicação do artefato de depuração.

**Ainda precisa de teste em dispositivo real:** autorização do Android, negociação TLS/autenticação com um servidor ativo, tráfego de ida e volta pelo TUN, DNS, rotas, desconexão e reconexão. Uma compilação bem-sucedida não comprova que um servidor público esteja online ou aceite o perfil.

## Servidores e privacidade

Os servidores públicos VPN Gate são operados por terceiros, podem ficar offline e não são operados pelo LivreNet. Evite usá-los para informações sensíveis e consulte as condições e a política de privacidade do operador. Este aplicativo não cria credenciais de servidor nem promete internet móvel gratuita ou ilimitada.

Para um túnel funcional, o perfil precisa apontar para um servidor OpenVPN acessível e autorizado. Não inventamos endereços, certificados ou chaves.

## Compilar

O workflow `.github/workflows/android-debug-apk.yml` compila o núcleo nativo, executa `gradle assembleDebug` e publica o artefato `livrenet-android-debug`. O APK de depuração serve para testes e não é uma versão assinada para publicação na Play Store.
