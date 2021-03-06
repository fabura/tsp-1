# Описание языка правил

## Литералы
```
123 
45.678
'90abc'
```
### Литералы времени
Примеры:
```
2 hours -- 2 часа
1.5 hr -- 1.5 часа (то же, что 90 minutes)
10 minutes -- 10 минут
3 min -- 3 минуты
0.75 seconds -- 0.75 секунды (то же, что 750 ms)
20 sec -- 20 секунд
568 milliseconds -- 568 миллисекунд
100 ms -- 100 миллисекунд
```
_Примечание: можно указывать дробное число миллисекунд,
но это все равно будет округляться до целой миллисекунды._

Можно комбинировать различные единицы: 

```
2 hours 20 minutes 16 seconds 358 ms  (то же, что 8416358 ms и т.п.)
```

## Идентификаторы (названия полей в базе)
```
SomeField
"Some field with spaces"
```

## Операторы
Поддерживаются:
- арифметические операторы `+`, `-`, `*`, `/`
- операторы сравнения `<`, `<=`, `>`, `>=`, `=`, `!=`, `<>`
- логические (булевские) операторы `and`, `or`, `not`, `xor`

## Арифметические выражения
Поддерживаются выражения, содержащие арифметические операторы над
числовыми литерами и идентификаторами (пример: `Column1 + Column2 * 2`)

## Логические выражения
Поддерживаются сравнения арифметических выражений, возможно
соединенные логическими операторами (пример: `Col1 < Col2 and Col3 != 0`)

## Условия
(Здесь `<expr>` - некоторое логическое выражение.)
- выполнение выражения в течение указанного временного окна
```
<expr> for <time_window>
```
где `<time_window>` - литерал времени
- выполнение выражения в течение указанного временного окна
 (всегда дожидаться окончания)
```
<expr> for exactly <time_window>
```
- ограничение на общее время выполнения условия в течение окна,
где `<time_cond>` - одно из следующих выражений:
    * сравнение с временным литералом, пример: `< 5 seconds` -
      менее 5 секунд
    * границы (литералы), разделенные оператором `to`, пример:
      `30 sec to 1 min` - от 30 секунд до 1 минуты
      (Если единица измерения одна и та же у обеих границ,
      то ее можно указывать только один раз, например:
      `5 to 20 sec` - от 5 до 20 секунд.)
```
<expr> for [exactly] <time_window> <time_cond>
```

- ограничение на количество выполнений условия в течение окна,
где `<times_cond>` - одно из следующих выражений:
    * сравнение с количеством раз, пример: `<= 5 times` -
      не более 5 раз
    * границы (литералы), разделенные оператором `to`, пример:
      `5 to 10 times` - от 5 до 10 раз
```
<expr> for [exactly] <time_window> <times_cond>
```

- выполнение выражения до наступления условия:
```
<expr> until <cond>
```

Условия могут объединяться операторами `and`, `andThen`, `or`.

## Функции
- `avg(<expr>, <time>)` - среднее значение выражения `<expr>`
  за время `<time>`;
- `max(<expr>, <time>)` - наибольшее значение выражения `<expr>`
за время `<time>`;
- `min(<expr>, <time>)` - наименьшее значение выражения `<expr>`
  за время `<time>`;
- `abs(<expr>)` - абсолютная величина выражения `<expr>`;
- `avgOf(<expr1>, <expr2>, ..., <exprN>; [<cond>])` - среднее из
значений `<expr1>`, `<expr2>`, ..., `<exprN>`, для которых выполняется
условие `<cond>` (логическое выражение, не содержащее переменных, за
исключением служебного значения `_`, на место которого подставляется
поочередно значение каждой из колонок). `NaN` - значения
игнорируются в любом случае;
- `maxOf(<expr1>, <expr2>, ..., <exprN>; [<cond>])` и
  `minOf(<expr1>, <expr2>, ..., <exprN>; [<cond>])` - соответственно
  наибольшее и наименьшее значения, все прочее аналогично функции
  `avgOf`.