import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';

/** 방 입장이 막힌 이유 — `full` 은 자리 없음(꽉 참·이미 시작), `gone` 은 방 없음. */
export type JoinBlock = 'full' | 'gone';

interface JoinBlockedPanelProps {
  kind: JoinBlock;
  /** `full` 에서만 — 관전으로 들어가기. */
  onSpectate?: () => void;
  spectating?: boolean;
}

/**
 * D-133 — 초대 링크로 들어왔는데 입장이 막혔을 때. 서버 오류 문구를 그대로 보이지 않고 다음 행동을 제안한다:
 * 자리가 없으면 관전, 방이 없으면 메인으로.
 */
export function JoinBlockedPanel({ kind, onSpectate, spectating = false }: JoinBlockedPanelProps) {
  const full = kind === 'full';
  return (
    <div className="app-shell flex min-h-screen items-center justify-center bg-background p-4 text-foreground">
      <Card className="w-full max-w-sm">
        <CardHeader>
          <CardTitle className="text-xl">{full ? '자리가 없어요 — 관전할까요?' : '방이 없어졌어요'}</CardTitle>
          <CardDescription>
            {full
              ? '방이 꽉 찼거나 게임이 이미 시작됐어요. 관전하면 손패 없이 게임을 지켜볼 수 있어요.'
              : '초대받은 방이 끝났거나 모두 나갔어요. 메인에서 다른 방을 찾아보세요.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          {full && (
            <Button type="button" onClick={onSpectate} disabled={spectating}>
              관전하기
            </Button>
          )}
          <Button asChild variant="outline">
            <Link to="/games">메인으로</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}
