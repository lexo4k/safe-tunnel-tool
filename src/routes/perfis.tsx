import { createFileRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { FileKey2, Upload, ShieldCheck, Trash2, CheckCircle2, Server, BookOpen, CircleHelp } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Notice, PageHeading } from '@/components/livrenet/shared';
import { useLivreNet } from '@/components/livrenet/state';
import { validateProfile } from '@/lib/vpn-profile';
import { pageHead } from '@/lib/page-head';

export const Route = createFileRoute('/perfis')({
 head: () => pageHead('Meus perfis', 'Importe um perfil VPN existente ou veja como obter uma configuração real de um servidor autorizado.'),
 component: ProfilesPage
});

function ProfilesPage() {
 const { profile, setProfile, authorized, setAuthorized } = useLivreNet();
 const [raw,setRaw] = useState('');
 const [name,setName] = useState('Meu perfil');
 const [error,setError] = useState('');
 const [message,setMessage] = useState('');
 const [protocol,setProtocol] = useState('WireGuard');
 const [showGuide,setShowGuide] = useState(false);
 const fileInput = useRef<HTMLInputElement>(null);

 async function importFile(file?: File) {
  setError(''); setMessage('');
  if (!file) return;
  if (!/\.(conf|ovpn)$/i.test(file.name)) {setError('Escolha um arquivo .conf ou .ovpn.'); return;}
  if (file.size > 65536) {setError('O arquivo deve ter no máximo 64 KB.');return;}
  try {
   const content = await file.text();
   setRaw(content);
   setName(file.name.replace(/\.(conf|ovpn)$/i,'').slice(0,60));
   setProtocol(file.name.endsWith('.ovpn') ? 'OpenVPN' : 'WireGuard');
   setMessage('Arquivo lido somente nesta aba. Valide para conferir a estrutura.');
  } catch {setError('Não foi possível ler o arquivo. Tente selecionar novamente.');}
 }

 function validate() {
  setError(''); setMessage('');
  const result = validateProfile(raw,name);
  if(result.error) {setError(result.error);return;}
  if(result.profile) {
   setProfile(result.profile);
   setAuthorized(false);
   setRaw('');
   setMessage('Estrutura reconhecida. Validação apenas superficial: chaves, certificados, autorização e servidor não foram verificados. O túnel não foi iniciado. O conteúdo sensível foi descartado; só o resumo permanece nesta sessão.');
  }
 }

 return <>
  <PageHeading title="Meus perfis" subtitle="Escolha como começar: importe uma configuração que você já tem ou veja como obter uma."/>
  <Notice><strong>Importante:</strong> este painel web não consegue ativar uma VPN no Android. Para um túnel real é necessário um servidor VPN online, uma configuração válida e um cliente VPN nativo.</Notice>
  <div className="mt-7 grid items-start gap-7 lg:grid-cols-[1.4fr_1fr]">
   <section className="panel p-6" id="importar-perfil">
    <div className="panel-heading"><h2>Já tenho um perfil</h2><span className="status-badge cyan"><ShieldCheck size={11}/>Local e privado</span></div>
    <p className="mt-3 text-xs leading-6 text-muted-foreground">Use um arquivo .conf do WireGuard ou .ovpn do OpenVPN fornecido pelo administrador do servidor ou pelo serviço VPN que você contratou.</p>
    <form className="mt-6 space-y-5" onSubmit={event => {event.preventDefault();validate();}}>
     <div><label htmlFor="profile-name" className="mb-2 block text-xs font-semibold">Nome do perfil</label><input id="profile-name" className="field" value={name} maxLength={60} onChange={e=>setName(e.target.value)} autoComplete="off"/></div>
     <fieldset><legend className="mb-2 text-xs font-semibold">Formato de referência</legend><div className="flex gap-2">{['WireGuard','OpenVPN'].map(value=><Button key={value} type="button" variant={protocol===value ? 'secondary':'outline'} aria-pressed={protocol===value} onClick={()=>setProtocol(value)}>{value}</Button>)}</div><p className="mt-2 text-[11px] text-muted-foreground">O formato será reconhecido pelo conteúdo, sem executar instruções.</p></fieldset>
     <div className="border border-dashed border-border p-6 text-center"><Upload size={24} className="mx-auto text-primary"/><p className="mt-3 text-xs text-muted-foreground">Arquivo {protocol==='WireGuard'?'.conf':'.ovpn'} · Até 64 KB</p><Button type="button" variant="outline" className="mt-4" onClick={()=>fileInput.current?.click()}><Upload/>Selecionar arquivo</Button><input ref={fileInput} type="file" accept=".conf,.ovpn" className="sr-only" aria-label="Importar arquivo de perfil" onChange={event=>{void importFile(event.target.files?.[0]);event.target.value='';}}/></div>
     <div><label htmlFor="profile-content" className="mb-2 block text-xs font-semibold">Ou cole seu perfil</label><textarea id="profile-content" className="field secret-input" rows={7} value={raw} maxLength={65536} placeholder="Cole aqui o conteúdo do perfil fornecido pelo servidor" autoComplete="off" autoCorrect="off" autoCapitalize="off" spellCheck={false} onChange={e=>{setRaw(e.target.value);setError('');setMessage('');}} aria-describedby="profile-help"/><p id="profile-help" className="mt-2 text-[11px] leading-5 text-muted-foreground">O conteúdo é usado somente nesta aba. Não cole chaves de terceiros nem perfis de origem desconhecida.</p></div>
     {error&&<p role="alert" className="text-xs leading-6 text-destructive">{error}</p>}
     {message&&<p role="status" className="text-xs leading-6 text-success">{message}</p>}
     <div className="flex flex-wrap gap-3"><Button type="submit"><ShieldCheck/>Validar localmente</Button><Button type="button" variant="ghost" onClick={()=>{setRaw('');setError('');setMessage('Conteúdo do editor descartado.');}}>Limpar editor</Button></div>
    </form>
   </section>
   <section>
    <h2 className="mb-4 text-base font-semibold">Seu perfil</h2>
    {profile ? <div className="panel p-6"><div className="flex items-center justify-between"><span className="icon-box"><FileKey2 size={19}/></span><span className="status-badge amber">Não conectado</span></div><h3 className="mt-5 break-words font-semibold">{profile.name}</h3><p className="mt-2 text-xs text-muted-foreground">{profile.protocol} · Validação superficial</p><p className="mt-4 text-xs leading-6 text-muted-foreground">{profile.hasEndpoint ? 'Uma referência de servidor foi encontrada, mas não foi verificada.' : 'Nenhum endereço de servidor encontrado. Solicite um perfil completo ao administrador.'}</p><label className="mt-5 flex items-start gap-3 text-xs leading-6"><input type="checkbox" className="mt-1.5 accent-primary" checked={authorized} onChange={e=>setAuthorized(e.target.checked)}/><span>Declaro que tenho autorização para usar este servidor. Isso não verifica o servidor.</span></label><Button variant="outline" className="mt-5" onClick={()=>{setProfile(null);setAuthorized(false);setMessage('Perfil removido da sessão.');}}><Trash2/>Remover perfil</Button></div> : <div className="panel p-5"><div className="flex items-center gap-3"><span className="icon-box"><CircleHelp size={20}/></span><div><h3 className="text-sm font-semibold">Ainda não tem um perfil?</h3><p className="mt-1 text-xs text-muted-foreground">Tudo bem. Veja os próximos passos.</p></div></div><Button className="mt-4 w-full" onClick={()=>setShowGuide(value=>!value)} variant={showGuide ? 'secondary' : 'default'}><BookOpen size={16}/>{showGuide ? 'Ocultar instruções' : 'Não tenho um perfil VPN'}</Button>{showGuide && <div className="mt-5 space-y-4 text-xs leading-6"><div className="flex gap-3"><Server size={17} className="mt-1 shrink-0 text-primary"/><div><strong>1. Obtenha um servidor</strong><p className="text-muted-foreground">Escolha um serviço VPN confiável que forneça configurações WireGuard/OpenVPN, ou configure um servidor seu em uma máquina ou VPS acessível pela internet.</p></div></div><div className="flex gap-3"><ShieldCheck size={17} className="mt-1 shrink-0 text-primary"/><div><strong>2. Gere uma configuração para seu dispositivo</strong><p className="text-muted-foreground">No servidor, crie um cliente com chaves próprias, endereço permitido e endpoint correto. Baixe o arquivo .conf ou .ovpn gerado pelo próprio servidor ou provedor.</p></div></div><div className="flex gap-3"><CheckCircle2 size={17} className="mt-1 shrink-0 text-primary"/><div><strong>3. Conecte pelo cliente adequado</strong><p className="text-muted-foreground">No Android, importe o arquivo no cliente WireGuard/OpenVPN ou no app nativo LivreNet quando essa integração estiver concluída. Autorize o pedido de VPN do Android quando aparecer.</p></div></div><Notice>Não é possível gerar aqui um perfil funcional sem os dados de um servidor real. Endereços e chaves inventados não conectam. Uma VPN também não substitui uma conexão móvel ou Wi-Fi existente.</Notice></div>}</div>}
    <div className="mt-7 space-y-4"><h3 className="flex items-center gap-2 text-sm font-semibold"><ShieldCheck size={17} className="text-primary"/>Privacidade por padrão</h3>{['Nenhum segredo em banco ou logs','Nenhuma conexão ao endpoint importado','Nenhuma execução de scripts do perfil','Resumo apagado ao recarregar a aba'].map(item=><p key={item} className="flex items-start gap-2 text-xs leading-5 text-muted-foreground"><CheckCircle2 size={14} className="mt-0.5 shrink-0 text-success"/>{item}</p>)}</div>
    <div className="mt-7"><Notice>Use apenas configurações de origem conhecida. Desconfie de promessas de internet grátis ou ilimitada.</Notice></div>
   </section>
  </div>
  <div className="h-8"/>
 </>;
}
