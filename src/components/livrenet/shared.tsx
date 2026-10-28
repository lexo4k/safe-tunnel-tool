import { Link } from '@tanstack/react-router';
import { useEffect, useState, type ReactNode } from 'react';
import { ArrowRight, Activity, Check, CircleHelp, FileKey2, Globe2, Info, LayoutDashboard, Leaf, LockKeyhole, Power, ShieldCheck, Wifi, X, AlertTriangle } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { useLivreNet } from './state';
import { NativeClients } from './native-clients';

export const navigation = [
  { to: '/' as const, label: 'Visão geral', mobile: 'Início', icon: LayoutDashboard },
  { to: '/servidores' as const, label: 'Servidores', mobile: 'Servidores', icon: Globe2 },
  { to: '/perfis' as const, label: 'Meus perfis', mobile: 'Perfis', icon: FileKey2 },
  { to: '/diagnostico' as const, label: 'Diagnóstico', mobile: 'Diagnóstico', icon: Activity },
  { to: '/dados' as const, label: 'Uso de dados', mobile: 'Dados', icon: Activity },
  { to: '/economia' as const, label: 'Internet a baixo custo', mobile: 'Economia', icon: Leaf },
];
export function Brand() { return <div className="brand"><ShieldCheck className="brand-mark" strokeWidth={1.8}/><span>Livre<span className="brand-net">Net</span></span></div>; }
export function AppShell({ children }: { children: ReactNode }) {
  return <><a href="#conteudo" className="skip-link">Pular para conteúdo</a><aside className="app-sidebar"><Link to="/" aria-label="LivreNet, início"><Brand/></Link><p className="mt-2 pl-11 text-[10px] text-muted-foreground">Sua conexão, sob controle.</p><p className="sidebar-label">ESPAÇO DE CONEXÃO</p><nav aria-label="Navegação principal" className="space-y-1">{navigation.map(item => <Link key={item.to} to={item.to} activeOptions={{ exact: true }} className="nav-item" activeProps={{ 'data-active': true }}><item.icon size={18} strokeWidth={1.7}/>{item.label}</Link>)}</nav><div className="mt-8 px-3"><span className="status-badge cyan"><LockKeyhole size={11}/> PRIVACIDADE EM PRIMEIRO LUGAR</span><p className="mt-3 text-[11px] leading-6 text-muted-foreground">Seus perfis ficam com você.<br/>Nenhum segredo é enviado.</p></div><div className="sidebar-footer"><Link to="/economia" className="flex items-center gap-2 text-xs text-muted-foreground"><CircleHelp size={16}/>Ajuda e uso responsável<ArrowRight size={13} className="ml-auto"/></Link><p className="mt-5 text-[10px] text-muted-foreground">LivreNet · Protótipo web v1.0</p></div></aside><div className="app-main"><header className="app-header"><div className="hidden items-center gap-2 text-xs text-muted-foreground md:flex"><span className="text-foreground font-semibold">Meu espaço</span><span className="mx-2">/</span>Painel de conexões</div><div className="md:hidden"><Link to="/" aria-label="LivreNet, início"><Brand/></Link></div><div className="flex shrink-0 items-center gap-4"><span className="status-badge amber"><span className="dot"/>Protótipo web</span><span title="Espaço local" className="hidden h-8 w-8 items-center justify-center rounded-full border border-border bg-secondary text-[11px] font-bold sm:flex">LN</span></div></header><main id="conteudo" className="app-content enter">{children}<footer className="page-footer"><span>© 2026 LivreNet. Conexões legítimas. Escolhas conscientes.</span><span className="flex items-center gap-1.5"><ShieldCheck size={12}/>Local por padrão · Sem túnel VPN ativo</span></footer></main></div><nav className="mobile-nav" aria-label="Navegação móvel">{navigation.map(item => <Link key={item.to} to={item.to} activeOptions={{ exact: true }} activeProps={{ 'data-active': true }}><item.icon size={19}/><span>{item.mobile}</span></Link>)}</nav></>;
}
export function PageHeading({ title, subtitle, action }: { title: string; subtitle: string; action?: ReactNode }) { return <div className="page-heading"><div className="min-w-0"><h1>{title}</h1><p>{subtitle}</p></div>{action && <div className="heading-action">{action}</div>}</div>; }
export function Notice({ children }: { children?: ReactNode }) { return <div className="notice"><Info size={17} className="mt-0.5 text-primary"/><div>{children ?? <><strong>VPN não cria internet.</strong> Você precisa de internet móvel ou Wi-Fi autorizado e de um servidor VPN autorizado. Este painel web não inicia um túnel VPN.</>}</div></div>; }
export function ConnectionButton() {
  const [open,setOpen] = useState(false);
   return <><Button size="lg" onClick={() => setOpen(true)}><Power size={16}/>Conectar em outro app</Button><Dialog open={open} onOpenChange={setOpen}><DialogContent><DialogTitle>Conexão por um cliente VPN</DialogTitle><DialogDescription className="leading-7">O LivreNet é um painel web. O túnel precisa ser criado por um aplicativo instalado no seu dispositivo; nenhum túnel foi iniciado aqui.</DialogDescription><NativeClients/><Button asChild variant="outline"><Link to="/perfis" onClick={() => setOpen(false)}><FileKey2 size={16}/>Gerenciar perfil local</Link></Button></DialogContent></Dialog></>;
}
export function useNetwork() {
  const [online, setOnline] = useState<boolean | null>(null);
  useEffect(() => { const update = () => setOnline(navigator.onLine); update(); window.addEventListener('online',update); window.addEventListener('offline',update); return () => { window.removeEventListener('online',update); window.removeEventListener('offline',update); }; },[]);
  return online;
}
export function Checklist() {
  const online = useNetwork();
  const { profile, authorized } = useLivreNet();
  const rows = [
    { title:'Conexão subjacente', detail:online === null ? 'Aguardando navegador' : online ? 'Navegador indica uma rede' : 'Navegador indica modo offline', state:online ? 'success' : '', icon: Wifi, label: online ? 'Detectada' : 'Pendente' },
    { title:'Internet alcançável', detail:'Execute um teste de alcance', state:'', icon:Globe2, label:'Não testada' },
    { title:'Servidor autorizado', detail:profile?.hasEndpoint && authorized ? 'Informado por você; não verificado' : 'Configure um perfil próprio', state:profile?.hasEndpoint && authorized ? 'warning' : '', icon:ShieldCheck, label:profile?.hasEndpoint && authorized ? 'Declarado' : 'Pendente' },
    { title:'Permissão VPN no Android', detail:'Requer aplicativo nativo', state:'warning', icon:LockKeyhole, label:'Fora do web' },
  ];
  return <div>{rows.map(row => <div className="check-row" key={row.title}><span className={`check-icon ${row.state}`}>{row.state === 'success' ? <Check size={14}/> : row.state === 'warning' ? <AlertTriangle size={13}/> : <row.icon size={13}/>}</span><div className="min-w-0"><p className="text-[11px] font-semibold">{row.title}</p><p className="mt-1 text-[10px] text-muted-foreground">{row.detail}</p></div><span className={`text-[9px] ${row.state === 'success' ? 'text-success' : row.state === 'warning' ? 'text-warning' : 'text-muted-foreground'}`}>{row.label}</span></div>)}</div>;
}
export const servers = [ {country:'Brasil', city:'São Paulo', flag:'🇧🇷', code:'BR'}, {country:'Estados Unidos',city:'Nova York',flag:'🇺🇸',code:'US'}, {country:'Portugal',city:'Lisboa',flag:'🇵🇹',code:'PT'} ];
export function ServersTable() {
  const [selected,setSelected] = useState<string|null>(null);
  return <><div className="overflow-x-auto"><table className="server-table"><thead><tr><th>LOCALIZAÇÃO</th><th>LATÊNCIA</th><th className="server-status">STATUS</th><th className="text-right">AÇÃO</th></tr></thead><tbody>{servers.map(server => <tr key={server.code}><td><div className="flex items-center"><span className="flag" aria-label={server.country}>{server.flag}</span><div><p className="font-semibold">{server.country}</p><p className="mt-1 text-[10px] text-muted-foreground">{server.city} · Demonstração</p></div></div></td><td><span className="text-muted-foreground">—</span><p className="mt-1 text-[9px] text-muted-foreground">Não medida</p></td><td className="server-status"><span className="status-badge"><span className="dot"/>Não conectado</span></td><td className="text-right"><Button variant="outline" size="sm" onClick={() => setSelected(server.country)}><Activity size={12}/>Testar</Button></td></tr>)}</tbody></table></div><Dialog open={selected !== null} onOpenChange={value => {if (!value) setSelected(null);}}><DialogContent><DialogTitle>Teste indisponível · {selected}</DialogTitle><DialogDescription className="leading-7">Este servidor é um exemplo demonstrativo, sem endereço real configurado. Nenhum teste de latência ou conexão foi executado.</DialogDescription><Notice>Importe um perfil de um servidor autorizado. Um teste VPN real exige integração com um cliente nativo e o servidor; o navegador não mede a latência do túnel.</Notice><Button onClick={() => setSelected(null)}>Entendi</Button></DialogContent></Dialog></>;
}
export function EmptyChart() {return <div className="chart-empty"><div className="chart-empty-message"><Activity size={24} strokeWidth={1.3}/><span>Nenhum tráfego medido</span><span className="text-[10px]">O painel web não monitora o túnel VPN.</span></div></div>;}
