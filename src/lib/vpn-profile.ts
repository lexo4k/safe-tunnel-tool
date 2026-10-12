import { z } from 'zod';

export type ProfileSummary = { name: string; protocol: 'WireGuard' | 'OpenVPN'; hasEndpoint: boolean };
const profileSchema = z.string().trim().min(1, 'Cole ou importe um perfil antes de validar.').max(65536, 'O perfil deve ter no máximo 64 KB.').refine(value => !value.includes('\0'), 'O arquivo contém caracteres inválidos.');
const nameSchema = z.string().trim().min(1, 'Informe um nome para o perfil.').max(60, 'Use até 60 caracteres no nome.').regex(/^[\p{L}\p{N}\s._()-]+$/u, 'O nome contém caracteres não permitidos.');

export function validateProfile(raw: string, name: string): { profile?: ProfileSummary; error?: string } {
  const text = profileSchema.safeParse(raw);
  const label = nameSchema.safeParse(name);
  if (!text.success) return { error: text.error.issues[0]?.message ?? 'Perfil inválido.' };
  if (!label.success) return { error: label.error.issues[0]?.message ?? 'Nome inválido.' };
  const value = text.data;
  if (/^\s*\[Interface\]\s*$/im.test(value)) {
    if (!/^\s*PrivateKey\s*=\s*\S+/im.test(value) || !/^\s*\[Peer\]\s*$/im.test(value) || !/^\s*PublicKey\s*=\s*\S+/im.test(value)) return { error: 'WireGuard incompleto: são necessários Interface, PrivateKey, Peer e PublicKey. Nenhuma chave foi exibida.' };
    return { profile: { name: label.data, protocol: 'WireGuard', hasEndpoint: /^\s*Endpoint\s*=\s*\S+/im.test(value) } };
  }
  if (/^\s*client\s*$/im.test(value) && /^\s*(dev\s+(tun|tap)\S*|<connection>)\s*$/im.test(value)) {
    if (/^\s*(up|down|route-up|plugin|script-security|ipchange|learn-address|tls-verify|auth-user-pass-verify|client-connect|client-disconnect)\b/im.test(value)) return { error: 'Perfil OpenVPN contém diretivas de execução externa. Não importe arquivos desconhecidos; solicite um perfil seguro ao administrador.' };
    return { profile: { name: label.data, protocol: 'OpenVPN', hasEndpoint: /^\s*remote\s+\S+/im.test(value) } };
  }
  return { error: 'Formato não reconhecido. Importe um arquivo .conf (WireGuard) ou .ovpn (OpenVPN) fornecido por um administrador autorizado.' };
}
