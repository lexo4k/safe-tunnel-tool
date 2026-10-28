import { ExternalLink, ShieldCheck } from 'lucide-react';
import { Button } from '@/components/ui/button';

export function NativeClients({ protocol }: { protocol?: 'WireGuard' | 'OpenVPN' }) {
  const clients = [
    { name: 'WireGuard', url: 'https://www.wireguard.com/install/' },
    { name: 'OpenVPN', url: 'https://openvpn.net/client/' },
  ].filter(client => !protocol || client.name === protocol);

  return <section aria-label="Conexão VPN fora do navegador" className="mt-6 space-y-4">
    <h3 className="flex items-center gap-2 text-sm font-semibold"><ShieldCheck size={17} className="text-primary"/>Conectar com um cliente VPN</h3>
    <p className="text-xs leading-6 text-muted-foreground">Instale o cliente oficial no seu dispositivo e importe nele o arquivo original fornecido pelo seu servidor ou serviço VPN. Este painel não envia nem transfere seu perfil.</p>
    <div className="flex flex-wrap gap-3">{clients.map(client => <Button key={client.name} asChild variant="outline"><a href={client.url} target="_blank" rel="noopener noreferrer">Obter {client.name}<ExternalLink size={14}/></a></Button>)}</div>
    <p className="text-xs leading-6 text-muted-foreground">No Android, autorize a solicitação de VPN do aplicativo. A conexão depende de internet existente e de um servidor autorizado online; instalar o cliente não cria um servidor. Confira o estado da conexão no próprio cliente.</p>
  </section>;
}