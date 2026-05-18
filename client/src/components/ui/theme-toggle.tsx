import { Moon, Sun } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useThemeStore } from '@/features/theme/themeStore';

/** Phase 20a (D-76) — 라이트/다크 토글. `<html>.dark` + localStorage 영속. */
export function ThemeToggle() {
  const theme = useThemeStore((s) => s.theme);
  const toggle = useThemeStore((s) => s.toggle);
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon"
      onClick={toggle}
      aria-label={theme === 'dark' ? '라이트 모드로' : '다크 모드로'}
      title={theme === 'dark' ? '라이트 모드로' : '다크 모드로'}
    >
      {theme === 'dark' ? (
        <Sun className="h-4 w-4" />
      ) : (
        <Moon className="h-4 w-4" />
      )}
    </Button>
  );
}
