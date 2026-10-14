insert into contents (id, title, description, required_plan, duration_sec, current_version)
values
    ('spring', 'Spring', '양치기 소녀와 개가 숲의 오래된 정령들과 마주하며 겨울의 끝을 건너가는 이야기.', 'BASIC', 464, 1),
    ('coffee-run', 'Coffee Run', '커피 한 잔을 손에 든 채 삶의 여러 순간을 쉬지 않고 달려 지나가는 한 사람.', 'BASIC', 184, 1),
    ('wing-it', 'Wing It!', '헛간의 고양이와 개가 고물로 만든 로켓에 올라 하늘을 날아 보려 한다.', 'PREMIUM', 237, 1),
    ('elephants-dream', 'Elephants Dream', '거대한 기계 세계에서 노인 프루그가 젊은 에모에게 자신이 믿는 세상을 보여 주려 한다.', 'PREMIUM', 658, 1)
on conflict (id) do update
    set title = excluded.title, description = excluded.description, required_plan = excluded.required_plan,
        duration_sec = excluded.duration_sec, current_version = excluded.current_version;
