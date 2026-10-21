# Learning what matters from choices between two offers

Status: planned. Arbay is meant to be built around the fit score: every offer ranked by how well
it fits what the buyer wants as a whole, and a watch that pushes the offers that fit well enough
(today a fit alert on a vehicle search, with a floor the user picks: 60, 70, 80 or 90 %).

## The problem with asking for values

A purchase weighs pros against cons. A buyer can name what they care about, but rarely how much
one thing is worth against another: how many kilometres of extra mileage a lower price makes up
for, or whether a stronger engine is worth a longer drive to the seller. A limit they type is not
a wall either. Someone who filters by 150 km may well drive 160 for the right offer, and that is
the whole point of a score over a filter.

So the weights the score uses should not be asked for as numbers. They should be learned from what
the buyer actually prefers.

## Two offers, one choice

A sparkles button next to a search's wishes fills the weights in for the user. It shows two real
offers from the market, side by side, and asks which one they like better. Each answer says
something about the trade-off between the two: the cheaper van with more kilometres, or the dearer
one with fewer. A run of such choices pins down both what matters and how much, in detail, without
the user ever stating a weight.

Points the design has to settle:

- Which pair to show next: the pair whose answer the current weights are least sure about, so few
  choices are needed. Pairs drawn from live offers of the search itself, so the choice is about
  the market the user is in, not invented examples.
- What a choice teaches: a pairwise preference model (Bradley-Terry or a logistic model over the
  difference of the two offers' features) that turns answers into per-criterion weights and the
  shape of each criterion's curve (where a price or a distance stops mattering).
- Where the learned weights live: on the saved search on the server, like every other setting, so
  app, website and MCP read the same score.
- What the user sees: the two offers as they look in the results, the facts that differ between
  them brought forward, and a way to say "both are fine" or "neither".
- How it meets the score that exists: FitScore's hand-set weights are the starting point the
  answers move away from.
