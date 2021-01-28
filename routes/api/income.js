const router = require('express').Router();
const { Income } = require('../../models');

router.get('/', (req, res) => {
    Income.findAll({

    })
        .then(incomeData => {
            if (!incomeData) {
                res.status(404).json({ message: 'There is no income data' })
            }

            res.json(incomeData);
        })
        .catch(err => {
            console.log(err);
            res.status(500).json(err)
        })
});
