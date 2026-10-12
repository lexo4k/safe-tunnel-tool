import { createContext, useContext, useState, type ReactNode } from 'react';
import type { ProfileSummary } from '@/lib/vpn-profile';

type AppState = { profile: ProfileSummary | null; setProfile: (profile: ProfileSummary | null) => void; authorized: boolean; setAuthorized: (value: boolean) => void };
const StateContext = createContext<AppState | null>(null);
export function LivreNetProvider({ children }: { children: ReactNode }) {
  const [profile, setProfile] = useState<ProfileSummary | null>(null);
  const [authorized, setAuthorized] = useState(false);
  return <StateContext.Provider value={{ profile, setProfile, authorized, setAuthorized }}>{children}</StateContext.Provider>;
}
export function useLivreNet() {
  const state = useContext(StateContext);
  if (!state) throw new Error('LivreNet context unavailable');
  return state;
}
