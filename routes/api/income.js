const router = require('express').Router();
const { Income } = require('../../models');

router.get('/', (req, res) => {
    Income.findAll({

    })
        .then(dbIncomeData => {
            if (!dbIncomeData) {
                res.status(404).json({ message: 'There is no income data' })
            }

            res.json(dbIncomeData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err)
        })
});

router.post('/', (req, res) => {
    Income.create({
        name: req.body.name,
        amount: req.body.amount
    })
        .then(dbIncomeData => res.json(dbIncomeData))
        .catch(err => {
            console.log(err.message);
            res.status(500).json(err)
        })
})