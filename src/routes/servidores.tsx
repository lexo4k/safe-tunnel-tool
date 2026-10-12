import { createFileRoute, Link } from '@tanstack/react-router';
import { Globe2, FileKey2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Notice, PageHeading, ServersTable } from '@/components/livrenet/shared';
import { useLivreNet } from '@/components/livrenet/state';
import { pageHead } from '@/lib/page-head';
export const Route = createFileRoute('/servidores')({ head: () => pageHead('Servidores', 'Servidores de demonstração, sem conexão ou latência inventada. Configure apenas servidores VPN autorizados.'), component: ServersPage });
function ServersPage() {
 const {profile} = useLivreNet();
 return <><PageHeading title="Servidores" subtitle="Organize conexões autorizadas. Saiba exatamente o que está configurado." action={<Button asChild variant="outline"><Link to="/perfis"><FileKey2/>Importar perfil</Link></Button>}/><Notice/><section className="mt-6"><h2 className="mb-4 text-base font-semibold">Seu servidor</h2>{profile ? <div className="panel p-6"><p className="font-semibold">{profile.name}</p><p className="mt-2 text-xs text-muted-foreground">{profile.protocol} · {profile.hasEndpoint ? 'Endpoint identificado superficialmente' : 'Nenhum endpoint identificado'} · Não conectado</p><p className="mt-4 text-xs text-warning">A autorização e a disponibilidade não foram verificadas.</p></div> : <div className="flex flex-col items-center border border-dashed border-border py-12 text-center"><Globe2 size={32} className="text-muted-foreground"/><h3 className="mt-4 font-semibold">Nenhum servidor configurado</h3><p className="mt-2 max-w-md px-4 text-xs leading-6 text-muted-foreground">Importe um perfil fornecido por você ou pelo administrador de um servidor autorizado.</p><Button asChild className="mt-5"><Link to="/perfis">Adicionar perfil</Link></Button></div>}</section><section className="panel mt-7 mb-7 overflow-hidden"><div className="p-6"><h2 className="text-base font-semibold">Servidores de exemplo</h2><p className="mt-2 text-xs text-muted-foreground">Demonstração / não conectados. Não são endpoints disponíveis para uso.</p></div><ServersTable/></section></>;
}
