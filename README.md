# sprout-goals

**Goals**: pots of invested money, each saving towards something (a trip, a laptop, a deposit on a home)
with a target and, if the customer likes, a date. And **round-ups**: the spare change from everyday UPI
spends, swept into a pot and invested.

- **A pot invests in one share.** Money put in (from the Sprout balance, never more than the customer
  has free across all pots) buys whole shares at the market through the [order service](https://github.com/SaiNayakk/sprout-oms)
  on the customer's behalf, tagged `goal:<pot>`. What doesn't make a whole share waits in the pot.
- **Round-ups.** With AutoPay set up in [payments](https://github.com/SaiNayakk/sprout-payments) and approved in
  [Sprout Bank](https://github.com/SaiNayakk/sprout-bank), each UPI spend is rounded up to the next ₹10, ₹50 or
  ₹100, one to three times over. Once ₹100 is waiting it is swept in one AutoPay debit into the chosen pot.
- **Safe to repeat.** A spend is rounded up once (its id). A sweep's debit and a buy's order carry ids made
  before they are asked for, so asking again never takes or buys twice; an unknown outcome is asked again,
  never guessed. A refused sweep or buy waits an hour before it is tried again.
- A pot is `REACHED` when what it holds (its shares at the last price, plus anything uninvested) meets its
  target. Closing it stops new money; its shares stay in the customer's holdings.

## Part of Sprout

[Sprout](https://sainayakk.github.io/sprout-platform/) is a simulated brokerage built from scratch as
separate services, each with its own repository and contract. Architecture, environments and test
evidence live in [sprout-platform](https://github.com/SaiNayakk/sprout-platform); this service's API is
[`goals-v1.yaml`](https://github.com/SaiNayakk/sprout-contracts/blob/main/src/main/resources/sprout/contracts/openapi/goals-v1.yaml)
in sprout-contracts. It runs inside the **money** host.

`./mvnw verify` runs the tests on a real Postgres against stand-ins for accounts, market data, the order
service and payments (which can refuse a debit, or go quiet and answer later).

## License

MIT
