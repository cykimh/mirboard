import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  arrayMove,
  horizontalListSortingStrategy,
  sortableKeyboardCoordinates,
  useSortable,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { Card } from '@/types/tichu';
import { cardKey } from '@/types/tichu';
import { CardChip } from './CardChip';
import { useHandOverlap } from './useHandOverlap';

interface SortableCardProps {
  card: Card;
  selected: boolean;
  onClick: () => void;
}

function SortableCard({ card, selected, onClick }: SortableCardProps) {
  const id = cardKey(card);
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id });

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.4 : 1,
    touchAction: 'none',
  } as const;

  return (
    <div ref={setNodeRef} style={style} {...attributes} {...listeners}>
      <CardChip card={card} selected={selected} onClick={onClick} />
    </div>
  );
}

interface SortableHandProps {
  cards: Card[];
  selectedKeys: Set<string>;
  onCardClick: (card: Card) => void;
  onReorder: (fromKey: string, toKey: string) => void;
  /** true 면 한 줄에 맞춰 필요한 만큼만 겹친다(플레이 단계, #1). false 면 절대
   *  겹치지 않고 넘치면 줄바꿈(패스/딜링 단계 — 고르기 쉽게, #2). 기본 true. */
  overlap?: boolean;
}

/**
 * @dnd-kit 기반 손패 — 카드 클릭은 selected 토글 / 드래그는 재배열. PointerSensor
 * activation 거리 8px 로 short tap 과 drag 를 구분.
 */
export function SortableHand({
  cards,
  selectedKeys,
  onCardClick,
  onReorder,
  overlap = true,
}: SortableHandProps) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    // A5 — 키보드로도 손패 재배열 가능(Space 로 집고 화살표로 이동).
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );
  const ids = cards.map(cardKey);
  // overlap=false 면 0 을 넘겨 측정/겹침 비활성(클래스도 안 붙음).
  const handRef = useHandOverlap(overlap ? cards.length : 0);

  function handleDragEnd(event: DragEndEvent) {
    const { active, over } = event;
    if (!over || active.id === over.id) return;
    onReorder(String(active.id), String(over.id));
  }

  // arrayMove 는 별도 사용처 없음 — store reducer 가 직접 splice. 단지 import 가 SortableContext
  // 외부 사용자에게 친숙해서 같이 export 가능.
  void arrayMove;

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
      <SortableContext items={ids} strategy={horizontalListSortingStrategy}>
        <div className={`hand-cards${overlap ? ' overlap' : ''}`} ref={handRef}>
          {cards.map((c) => {
            const key = cardKey(c);
            return (
              <SortableCard
                key={key}
                card={c}
                selected={selectedKeys.has(key)}
                onClick={() => onCardClick(c)}
              />
            );
          })}
        </div>
      </SortableContext>
    </DndContext>
  );
}
